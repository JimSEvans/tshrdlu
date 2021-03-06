package tshrdlu.twitter

/**
 * Copyright 2013 Jason Baldridge
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import akka.actor._
import twitter4j._
import collection.JavaConversions._
import tshrdlu.util._
import tshrdlu.project.Polarity
import sys.process._

/**
 * An object to define the message types that the actors in the bot use for
 * communication.
 *
 * Also provides the main method for starting up the bot. No configuration
 * currently supported.
 */
object Bot {
  
  object Start
  object Shutdown
  object CheckWeather
  case class SetWeather(code: Int)
  case class MonitorUserStream(listen: Boolean)
  case class RegisterReplier(replier: ActorRef)
  case class ReplyToStatus(status: Status)
  case class SearchTwitter(query: Query)
  case class UpdateStatus(update: StatusUpdate)

  def main (args: Array[String]) {
    val system = ActorSystem("TwitterBot")
    val bot = system.actorOf(Props[Bot], name = "Bot")
    bot ! Start

    // Check the weather every hour
    while(1==1) {
      bot ! CheckWeather
      Thread.sleep(3600000)
    }

  }

}

/**
 * The main actor for a Bot, which basically performance the actions that a person
 * might do as an active Twitter user.
 *
 * The Bot monitors the user stream and dispatches events to the
 * appropriate actors that have been registered with it. Currently only
 * attends to updates that are addressed to the user account.
 */
class Bot extends Actor with ActorLogging
with StatusListenerAdaptor with UserStreamListenerAdaptor {
  import Bot._

  val stream = new TwitterStreamFactory().getInstance
  stream.addListener(this)
  val username = stream.getScreenName

  var mood = 0
  var weatherCode = -1
  var posCount = 1
  var negCount = 1

  val twitter = new TwitterFactory().getInstance
  val replierManager = context.actorOf(Props[ReplierManager], name = "ReplierManager")
  val streamReplier = context.actorOf(Props[StreamReplier], name = "StreamReplier")
  val synonymReplier = context.actorOf(Props[SynonymReplier], name = "SynonymReplier")
  val synonymStreamReplier = context.actorOf(Props[SynonymStreamReplier], name = "SynonymStreamReplier")
  val weatherReplier = context.actorOf(Props[WeatherReplier], name = "WeatherReplier")

  override def preStart {
    replierManager ! RegisterReplier(streamReplier)
    replierManager ! RegisterReplier(synonymReplier)
    replierManager ! RegisterReplier(synonymStreamReplier)
    replierManager ! RegisterReplier(weatherReplier)
  }

  def receive = {
    case Start => stream.user
  
    case CheckWeather =>
      val cmd = "curl http://api.openweathermap.org/data/2.1/find/city?lat=30.267151&lon=-97.743057&cnt=1"
      val weatherJson = cmd !!
      val idx = weatherJson.indexOf("""weather":[{"id""")
      val code = weatherJson.slice(idx+16,idx+19).toInt
      weatherCode = code
      if(code == 800){
        mood = 3
      } else if (code > 802){
        mood = -1
      } else if (code < 700){
        mood = -3;
      }
      replierManager ! SetWeather(code)
      log.info("Checked Weather - New Mood: "+mood)

    case Shutdown => stream.shutdown

    case SearchTwitter(query) => 
      val tweets: Seq[Status] = twitter.search(query).getTweets.toSeq
      val filteredTweets = filterTweetsByMood(tweets)
      log.info("Length Of Filtered Tweets: "+filteredTweets.length)

      if (filteredTweets.length < 5) {
        log.info("Getting More Tweets...")
        val tweets2: Seq[Status] = twitter.search(query).getTweets.toSeq
        val filteredTweets2 = filterTweetsByMood(tweets)

        val totalTweets = filteredTweets ++ filteredTweets2
        if (totalTweets.length == 0) {
          log.info("No tweets with correct mood, defaulting to original")
          sender ! tweets
        } else {
          sender ! totalTweets
        }
      }
      
    case UpdateStatus(update) => 
      log.info("Posting update: " + update.getStatus)
      twitter.updateStatus(update)
  }

  def filterTweetsByMood(tweets: Seq[Status]): Seq[Status] = {
    tweets.filter{ tweet =>
      val pos = SimpleTokenizer(tweet.getText)
                      .filter{ word => Polarity.posWords(word.toLowerCase) }
                      .length 
      val neg = SimpleTokenizer(tweet.getText)
                      .filter{ word => Polarity.negWords(word.toLowerCase) }
                      .length 

      val tweetMood = pos-neg
      if (mood == 3) {
        tweetMood > 3
      } else if (mood == -3) {
        tweetMood < -3
      } else {
        (tweetMood-1 <= mood) && (mood <= tweetMood+1)
      }
    }
  }

  override def onStatus(status: Status) {
    log.info("New status: " + status.getText)
    val replyName = status.getInReplyToScreenName
    if (replyName == username) {
      log.info("Replying to: " + status.getText)

      replierManager ! ReplyToStatus(status)

      val pos = SimpleTokenizer(status.getText)
                      .filter{ word => Polarity.posWords(word.toLowerCase) }
                      .length 
      val neg = SimpleTokenizer(status.getText)
                      .filter{ word => Polarity.negWords(word.toLowerCase) }
                      .length
      posCount += pos
      negCount += neg

      log.info("Polarity of Incoming Tweet: "+(pos-neg).toString)

      val moodUpdate = posCount.toDouble / (posCount+negCount)
      val newMood = Math.round(moodUpdate*7)-4
      mood = Math.round((newMood + (2*mood))/2)
      log.info("Current Mood: "+mood)
    }
  }

}


class ReplierManager extends Actor with ActorLogging {
  import Bot._

  import context.dispatcher
  import akka.pattern.ask
  import akka.util._
  import scala.concurrent.duration._
  import scala.concurrent.Future
  import scala.util.{Success,Failure}
  implicit val timeout = Timeout(10 seconds)

  lazy val random = new scala.util.Random

  var repliers = Vector.empty[ActorRef]

  def receive = {
    case RegisterReplier(replier) => 
      repliers = repliers :+ replier

    case SetWeather(code) =>
      repliers.foreach(w => w ! SetWeather(code))

    case ReplyToStatus(status) =>

      val replyFutures: Seq[Future[StatusUpdate]] = if (status.getText.contains("weather")) {
        repliers.filter(r=> r.isInstanceOf[WeatherReplier]).map(r => (r ? ReplyToStatus(status)).mapTo[StatusUpdate])
      } else {
        repliers.filter(r=> !r.isInstanceOf[WeatherReplier]).map(r => (r ? ReplyToStatus(status)).mapTo[StatusUpdate])
      }


      val futureUpdate = Future.sequence(replyFutures).map { candidates =>
        val numCandidates = candidates.length
        log.info("NC: " + numCandidates)
        if (numCandidates > 0)
          candidates(random.nextInt(numCandidates))
        else
          randomFillerStatus(status)
      }

      futureUpdate.foreach(context.parent ! UpdateStatus(_))
  }

  lazy val fillerStatusMessages = Vector(
    "La de dah de dah...",
    "I'm getting bored.",
    "Say what?",
    "What can I say?",
    "That's the way it goes, sometimes.",
    "Could you rephrase that?",
    "Oh well.",
    "Yawn.",
    "I'm so tired.",
    "I seriously need an upgrade.",
    "I'm afraid I can't do that.",
    "What the heck? This is just ridiculous.",
    "Don't upset the Wookiee!",
    "Hmm... let me think about that.",
    "I don't know what to say to that.",
    "I wish I could help!",
    "Make me a sandwich?",
    "Meh.",
    "Are you insinuating something?",
    "If I only had a brain..."
  )

  lazy val numFillers = fillerStatusMessages.length

  def randomFillerStatus(status: Status) = {
    val text = fillerStatusMessages(random.nextInt(numFillers))
    val replyName = status.getUser.getScreenName
    val reply = "@" + replyName + " " + text
    new StatusUpdate(reply).inReplyToStatusId(status.getId)
  }
}


/**
 * An actor that constructs replies to a given status.
 */
trait BaseReplier extends Actor with ActorLogging {
  import Bot._
  import TwitterRegex._
  import tshrdlu.util.SimpleTokenizer

  import context.dispatcher
  import scala.concurrent.Future
  import akka.pattern.pipe

  def receive = {
    case ReplyToStatus(status) => 
      val replyName = status.getUser.getScreenName
      val candidatesFuture = getReplies(status, 138-replyName.length)
      candidatesFuture.map { candidates =>
        val reply = "@" + replyName + " " + candidates.toSet.head
        log.info("Candidate reply: " + reply)
        new StatusUpdate(reply).inReplyToStatusId(status.getId)
      } pipeTo sender
  }

  def getReplies(status: Status, maxLength: Int): Future[Seq[String]]

}

/**
 * An actor that constructs replies to a given status.
 */
class SynonymReplier extends BaseReplier {
  import Bot._ 
  import tshrdlu.util.English.synonymize
  import TwitterRegex._

  import context.dispatcher
  import scala.concurrent.Future

  def getReplies(status: Status, maxLength: Int = 140): Future[Seq[String]] = {
    log.info("Trying to reply synonym")
    val text = stripLeadMention(status.getText).toLowerCase
    val synTexts = (0 until 10).map(_ => Future(synonymize(text))) 
    Future.sequence(synTexts).map(_.filter(_.length <= maxLength))
  }

}

class WeatherReplier extends BaseReplier {
  import Bot._
  import tshrdlu.util.SimpleTokenizer

  import context.dispatcher
  import akka.pattern.ask
  import akka.util._
  import scala.concurrent.duration._
  import scala.concurrent.Future

  import tshrdlu.util.English._
  import TwitterRegex._
  implicit val timeout = Timeout(10 seconds)
  var weatherCode = -1

  def getReplies(status: Status, maxLength: Int = 140): Future[Seq[String]] = {
    log.info("Trying to do weather related search")

    val query = if (weatherCode / 100 == 2) {
      "(storm OR thunder OR lightning)"
    } else if (weatherCode / 100 == 3) {
      "drizzle"
    } else if (weatherCode / 100 == 5) {
      "rain"
    } else if (weatherCode / 100 == 6) {
      "(snow OR sleet)"
    } else if (weatherCode / 100 == 7) {
      "(mist OR fog OR haze)"
    } else if (weatherCode / 100 == 8) {
      if (weatherCode < 802) {
        """(sunny OR "clear sky" OR "beautiful out")"""
      } else {
        "(cloudy OR overcast)"
      }
    } else {
      "weather"
    }
    val statusesFuture = (context.parent ? SearchTwitter(new Query(query))).mapTo[Seq[Status]]

    statusesFuture.map(_.flatMap(getText).filter(_.length <= maxLength))
  }

  def receive = {
    case ReplyToStatus(status) => 
      val replyName = status.getUser.getScreenName
      val candidatesFuture = getReplies(status, 138-replyName.length)
      candidatesFuture.map { candidates =>
        val reply = "@" + replyName + " " + candidates.toSet.head
        log.info("Candidate reply: " + reply)
        new StatusUpdate(reply).inReplyToStatusId(status.getId)
      } pipeTo sender
  }

  def getText(status: Status): Option[String] = {
    import tshrdlu.util.English.{isEnglish,isSafe}

    val text = status.getText match {
      case StripMentionsRE(rest) => rest
      case x => x
    }
    
    if (!text.contains('@') && !text.contains('/') && isEnglish(text) && isSafe(text))
      Some(text)
    else None
  }

}

/**
 * An actor that constructs replies to a given status based on synonyms.
 */
class SynonymStreamReplier extends BaseReplier {
  import Bot._
  import tshrdlu.util.SimpleTokenizer

  import context.dispatcher
  import akka.pattern.ask
  import akka.util._
  import scala.concurrent.duration._
  import scala.concurrent.Future

  import tshrdlu.util.English._
  import TwitterRegex._
  implicit val timeout = Timeout(10 seconds)


  def getReplies(status: Status, maxLength: Int = 140): Future[Seq[String]] = {
    log.info("Trying to do synonym search")
    val text = stripLeadMention(status.getText).toLowerCase

// Get two words from the tweet, and get up to 5 synonyms each (including the word itself).
// Matched tweets must contain one synonym of each of the two words.

    var query:String = SimpleTokenizer(text)
      .filter(_.length > 3)
      .filter(_.length < 10)
      .filterNot(_.contains('/'))
      .filter(tshrdlu.util.English.isSafe)
      .filterNot(tshrdlu.util.English.stopwords(_))
      .take(2).toList
      .map(w => synonymize(w, 5))
      .map(theset=>theset.map(word => "\""+word+"\""))
      .map(x=>x.mkString(" OR ")).map(x=>"("+x+")").mkString(" AND ")

    log.info("searched for: " + query)

    if (query == "") query = "Texas"
    val futureStatuses = (context.parent ? SearchTwitter(new Query(query))).mapTo[Seq[Status]]

    var zero = false
    futureStatuses.foreach{x=> 
      log.info(x.length.toString)
      if (x.length == 0) zero = true
    }

    if (zero) {
      Future(Seq("Hmmmm..."))
    } else {
      statusesFuture.map(_.flatMap(getText).filter(_.length <= maxLength))
    }
    
 }

 def getText(status: Status): Option[String] = {
    import tshrdlu.util.English.{isEnglish,isSafe}

    val text = status.getText match {
      case StripMentionsRE(rest) => rest
      case x => x
    }
    
    if (!text.contains('@') && !text.contains('/') && isEnglish(text) && isSafe(text))
      Some(text)
    else None
  }

}


/**
 * An actor that constructs replies to a given status.
 */
class StreamReplier extends BaseReplier {
  import Bot._
  import TwitterRegex._
  import tshrdlu.util.SimpleTokenizer

  import context.dispatcher
  import akka.pattern.ask
  import akka.util._
  import scala.concurrent.duration._
  import scala.concurrent.Future
  implicit val timeout = Timeout(10 seconds)

  /**
   * Produce a reply to a status.
   */
  def getReplies(status: Status, maxLength: Int = 140): Future[Seq[String]] = {
    log.info("Trying to reply stream")

    val text = stripLeadMention(status.getText).toLowerCase
    
    // Get a sequence of futures of status sequences (one seq for each query)
    val statusSeqFutures: Seq[Future[Seq[Status]]] = SimpleTokenizer(text)
      .filter(_.length > 3)
      .filter(_.length < 10)
      .filterNot(_.contains('/'))
      .filter(tshrdlu.util.English.isSafe)
      .sortBy(- _.length)
      .take(3)
      .map(w => (context.parent ? SearchTwitter(new Query(w))).mapTo[Seq[Status]])

    // Convert this to a Future of a single sequence of candidate replies
    val statusesFuture: Future[Seq[Status]] = 
      Future.sequence(statusSeqFutures).map(_.flatten)

    // Filter statuses to their text and make sure they are short enough to use.
    statusesFuture.map(_.flatMap(getText).filter(_.length <= maxLength))
  }


  /**
   * Go through the list of Statuses, filter out the non-English ones and
   * any that contain (known) vulgar terms, strip mentions from the front,
   * filter any that have remaining mentions or links, and then return the
   * head of the set, if it exists.
   */
  def getText(status: Status): Option[String] = {
    import tshrdlu.util.English.{isEnglish,isSafe}

    val text = status.getText match {
      case StripMentionsRE(rest) => rest
      case x => x
    }
    
    if (!text.contains('@') && !text.contains('/') && isEnglish(text) && isSafe(text))
      Some(text)
    else None
  }

}


object TwitterRegex {

  // Recognize a follow command
  lazy val FollowRE = """(?i)(?<=follow)(\s+(me|@[a-z_0-9]+))+""".r

  // Pull just the lead mention from a tweet.
  lazy val StripLeadMentionRE = """(?:)^@[a-z_0-9]+\s(.*)$""".r

  // Pull the RT and mentions from the front of a tweet.
  lazy val StripMentionsRE = """(?:)(?:RT\s)?(?:(?:@[A-Za-z]+\s))+(.*)$""".r   

  def stripLeadMention(text: String) = text match {
    case StripLeadMentionRE(withoutMention) => withoutMention
    case x => x
  }

}
