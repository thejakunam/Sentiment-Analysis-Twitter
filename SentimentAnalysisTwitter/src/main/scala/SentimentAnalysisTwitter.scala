import java.util.Properties
import Sentiment.Sentiment
import org.apache.spark.SparkConf
import org.apache.spark.streaming.twitter.TwitterUtils
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.spark.streaming.Seconds
import org.apache.spark.streaming.StreamingContext
import scala.collection.convert.wrapAll._
import edu.stanford.nlp.ling.CoreAnnotations
import edu.stanford.nlp.neural.rnn.RNNCoreAnnotations
import edu.stanford.nlp.pipeline.{Annotation, StanfordCoreNLP}
import edu.stanford.nlp.sentiment.SentimentCoreAnnotations

object Sentiment extends Enumeration {
  type Sentiment = Value
  val POSITIVE, NEGATIVE, NEUTRAL = Value

  def toSentiment(sentiment: Int): Sentiment = sentiment match {
    case x if x == 0 || x == 1 => Sentiment.NEGATIVE
    case 2 => Sentiment.NEUTRAL
    case x if x == 3 || x == 4 => Sentiment.POSITIVE
  }
}

object SentimentAnalysisTwitter {
  val props = new Properties()
  props.setProperty("annotators", "tokenize, ssplit, parse, sentiment")
  val pipeline: StanfordCoreNLP = new StanfordCoreNLP(props)

  private def getValue(text: String): Sentiment = {
    val list = findValue(text)
    val (_, sentiment) = if(list.nonEmpty) list
      .maxBy { case (sentence, _) => sentence.length }
    else findValue("key").maxBy{case (sentence, _) => sentence.length }
    sentiment
  }

  private def findValue(text: String): List[(String, Sentiment)] = {
    val annotation: Annotation = pipeline.process(text)
    val sentences = annotation.get(classOf[CoreAnnotations.SentencesAnnotation])
    sentences.map(sentence => (sentence, sentence.get(classOf[SentimentCoreAnnotations.SentimentAnnotatedTree])))
      .map { case (sentence, tree) => (sentence.toString, Sentiment.toSentiment(RNNCoreAnnotations.getPredictedClass(tree))) }
      .toList
  }

  def main(args: Array[String]) {

    if (args.length != 2) {
      println("Usage: spark-submit --class <class name> <path to jar> <query string> <topic>")
      System.exit(1)
    }

    val query = Seq(args(0))
    val topic = args(1)

    val apiKey = "eXiDQuLBw1vK5rQ6INjOREaih"
    val apiSecretKey  = "AYrwF5henaORYPh9Q4dLthOlkPpz3dXLWqhSOOSybHAzCBePfF"
    val accessToken = "1324555387152920576-1T2rmXP7mT7C5ROzbUFwb1aro1yHM0"
    val accessSecretToken = "zpqRrvF3Hl190sP6VkYpKkkhkJ7omSKytRJYpjdHNiIrR"

    System.setProperty("twitter4j.oauth.consumerKey", apiKey)
    System.setProperty("twitter4j.oauth.consumerSecret", apiSecretKey)
    System.setProperty("twitter4j.oauth.accessToken", accessToken)
    System.setProperty("twitter4j.oauth.accessTokenSecret", accessSecretToken)

    val sparkConf = new SparkConf().setAppName("Spark Streaming Application - Twitter Sentiment Analysis")
    if (!sparkConf.contains("spark.master")) {
      sparkConf.setMaster("local[*]")
    }

    val streamContextObj = new StreamingContext(sparkConf, Seconds(60))
    val twitterStreamObj = TwitterUtils.createStream(streamContextObj, None, query)
    val sentimentTagMap = twitterStreamObj.map(status =>status.getText()).map{hashTag=> (hashTag,getValue(hashTag))}

    sentimentTagMap.cache().foreachRDD(sentiment =>
      sentiment.foreachPartition(partition =>
        partition.foreach { x => {
          val strSerializer = "org.apache.kafka.common.serialization.StringSerializer"
          val props = new Properties()
          props.put("bootstrap.servers", "localhost:9092")
          props.put("key.serializer", strSerializer)
          props.put("value.serializer", strSerializer)
          val producerObj = new KafkaProducer[String, String](props)
          val consumerRecord = new ProducerRecord[String, String](topic, x._1.toString,x._2.toString)
          println(x)
          producerObj.send(consumerRecord)
          producerObj.close()
        }}
      ))
    streamContextObj.start()
    streamContextObj.awaitTermination()
  }
}

