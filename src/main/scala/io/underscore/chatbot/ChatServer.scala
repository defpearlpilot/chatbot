package io.underscore.chatbot

import io.circe._
import io.circe.generic.semiauto._
// import io.circe._

import io.circe.literal._
import org.http4s._
import org.http4s.dsl._

import cats.syntax.all._
import cats.effect.{IO, Sync}
import fs2.{Pipe, Sink, Stream, StreamApp, async}
import fs2.async.mutable.Topic
import java.io.File

import org.http4s._
import org.http4s.headers._
import org.http4s.circe._
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Location
import org.http4s.server.blaze.BlazeBuilder
import org.http4s.server.websocket._
import org.http4s.websocket.WebsocketBits.{Text, WebSocketFrame}

import scala.concurrent.ExecutionContext.Implicits.global

case class Room(name: String)
object Room {
  implicit val encoder: Encoder[Room] = deriveEncoder[Room]
  implicit val decoder: Decoder[Room] = deriveDecoder[Room]

  implicit val entityEncoder: EntityEncoder[IO, Room] = jsonEncoderOf[IO, Room]
  implicit val entityRunEncoder: EntityEncoder[IO, List[Room]] = jsonEncoderOf[IO, List[Room]]
}

case class Rooms(rooms: List[Room])
object Rooms {
  implicit val encoder: Encoder[Rooms] = deriveEncoder[Rooms]
  implicit val decoder: Decoder[Rooms] = deriveDecoder[Rooms]

  implicit val entityEncoder: EntityEncoder[IO, Rooms] = jsonEncoderOf[IO, Rooms]
  implicit val entityRunEncoder: EntityEncoder[IO, List[Rooms]] = jsonEncoderOf[IO, List[Rooms]]
}


object TopicMap {
  private var topicMap: Map[String, Topic[IO, String]] = Map[String, Topic[IO, String]]()

  def createTopic(name: String): Topic[IO, String] = {
    async.topic[IO, String](s"Welcome to $name").unsafeRunSync()
  }

  def getOrCreateTopic(name:String): Topic[IO, String] = {
    val option = topicMap.get(name)
    option match {
      case None =>
        val newTopic = createTopic(name)
        topicMap = topicMap + (name -> newTopic)
        newTopic
      case Some(topic) => topic
    }
  }
}


object ChatServer extends StreamApp[IO] with Http4sDsl[IO] {

  val DefaultTopic = "default"

  private val roomRef = async
    .refOf[IO, Map[String, Topic[IO, String]]](Map(DefaultTopic -> TopicMap.createTopic(DefaultTopic)))
    .unsafeRunSync()

  def createWebSocket(chatTopic: Topic[IO, String]): IO[Response[IO]] = {
    val toClient: Stream[IO, WebSocketFrame] =
      chatTopic
        .subscribe(100)
        .through(debug[IO,String]("toClient"))
        .map(t => Text(s"${t}"))
    val fromClient: Sink[IO, WebSocketFrame] =
      source => source
        .collect{ case Text(t, _) => t }
        .through(debug[IO,String]("fromClient"))
        .to(chatTopic.publish)

    WebSocketBuilder[IO].build(toClient, fromClient)
  }


  val service = HttpService[IO] {
    case _ @ GET -> Root =>
      TemporaryRedirect(Location(uri("/index.html")))

    case request @ GET -> Root / "index.html" =>
      StaticFile.fromFile(new File("site/index.html"), Some(request))
        .getOrElseF(NotFound()) // In case the file doesn't exist

    case _ @ GET -> Root / "chat" / "rooms" =>
      val chatTopicMap = roomRef.get.unsafeRunSync()
      Ok(Rooms(chatTopicMap.keySet.toList.map(Room(_))))

    case GET -> Root / "chat" / _ =>
      val chatTopic = TopicMap.getOrCreateTopic(DefaultTopic)
      createWebSocket(chatTopic)

    case _ @ POST -> Root / "chat" / "rooms2" / chatTopicName =>
      roomRef.modify(chatTopicMap => {
        val maybeTopic: Option[Topic[IO, String]] = chatTopicMap.get(chatTopicName)
        maybeTopic match {
          case Some(_) => chatTopicMap
          case None => chatTopicMap + (chatTopicName -> TopicMap.createTopic(chatTopicName))
        }
      })
//        .through(debug[IO, Map[String, Topic[IO, String]]]("rooms"))
        .unsafeRunSync()

//      val modifiedMap: Ref.Change[Map[String, Topic[IO, String]]] = modifiedRef.unsafeRunSync()
      Ok("Done!")


    case _ @ POST -> Root / "chat" / "rooms" / chatTopicName =>
      val chatTopicMap: Map[String, Topic[IO, String]] = roomRef.get.unsafeRunSync()
      val maybeTopic: Option[Topic[IO, String]] = chatTopicMap.get(chatTopicName)
      val updatedTopicMap: Map[String, Topic[IO, String]] = maybeTopic match {
        case Some(_) => chatTopicMap
        case None => {
          val updated: Map[String, Topic[IO, String]] = chatTopicMap + (chatTopicName -> TopicMap.createTopic(chatTopicName))
          roomRef.setSync(updated).unsafeRunSync()
          updated
        }
      }

      Ok(updatedTopicMap.keySet.toList.mkString(","))
//      val topic = updatedTopicMap(chatTopicName)
//      createWebSocket(topic)


    case _ @ GET -> Root / "chat" / "room" / path =>
      Ok(path)

  }

  def debug[F[_],A](prefix: String)(implicit sync: Sync[F]): Pipe[F,A,A] =
    in => in.evalMap(a => sync
      .suspend(println(s"$prefix: $a").pure[F])
      .map(_ => a))

  def stream(args: List[String], requestShutdown: IO[Unit]) =
    BlazeBuilder[IO]
      .bindHttp(8080, "0.0.0.0")
      .mountService(service, "/")
      .serve
}
