package controllers
import play.api.Logging
import javax.inject._
import play.api._
import play.api.http.HttpEntity

import java.nio.file.Paths
import play.api.libs.json._
import play.api.mvc._
import play.api.libs.ws._

import scala.concurrent.duration.DurationInt
import scala.concurrent.ExecutionContext
import scala.language.postfixOps

@Singleton
class HomeController @Inject()(
    cc: ControllerComponents,
    config: Configuration,
    ws: WSClient
) (implicit ec: ExecutionContext) extends AbstractController(cc) with Logging {

  def index() = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.index(config))
  }

  def add() = Action.async { implicit request: Request[AnyContent] =>
    logger.debug("Starting add method")
    ws
      .url(s"${config.get[String]("server_url")}/get_documents")
      .withRequestTimeout(5 minutes)
      .get()
      .map(files => {
        logger.info(s"Received documents: ${files.json}")
        Ok(views.html.add(files.json.as[Seq[String]]))
      })
      .recover {case ex => 
      logger.error("Error fetching documents", ex)
      InternalServerError("Failed to fetch documents")
      }

  }
  def getDocuments = Action { implicit request: Request[AnyContent] =>
    //  
    val documents = Seq("Document1", "Document2", "Document3")
    Ok(Json.toJson(documents)) // 
  }
  def search() = Action.async { implicit request: Request[AnyContent] =>
    logger.debug("Starting search method")
    val json = request.body.asJson.getOrElse(Json.obj()).as[JsObject]
    val query = (json \ "query").as[String]
    logger.debug(s"Query: $query")
    val history = (json \ "history").as[Seq[JsObject]]
    val docs = (json \ "docs").as[Seq[JsObject]]

    ws
      .url(s"${config.get[String]("server_url")}/chat")
      .withRequestTimeout(5 minutes)
      .post(Json.obj(
        "prompt" -> query,
        "history" -> history,
        "docs" -> docs
      ))
      .map(response => {
          logger.info(s"Search response: ${response.json}")
          Ok(response.json)
      })
      .recover { case ex => 
      logger.error("Error during search", ex)
      InternalServerError("Failed to perform search")
      }
  }

  def download(file: String) = Action.async { implicit request: Request[AnyContent] =>
    logger.debug(s"Starting download for file: $file")
    ws.url(s"${config.get[String]("server_url")}/get_document")
      .withRequestTimeout(5.minutes)
      .post(Json.obj("filename" -> file))
      .map { response =>
        logger.info(s"Download response status: ${response.status}")
        if (response.status == 200) {
          logger.debug(s"File $file successfully downloaded.")
          // Get the content type and filename from headers
          val contentType = response.header("Content-Type").getOrElse("application/octet-stream")
          val disposition = response.header("Content-Disposition").getOrElse("")
          val filenameRegex = """filename="?(.+)"?""".r
          val downloadFilename = filenameRegex.findFirstMatchIn(disposition).map(_.group(1)).getOrElse(file)

          // Stream the response body to the user
          Result(
            header = ResponseHeader(200, Map(
              "Content-Disposition" -> s"""attachment; filename="$downloadFilename"""",
              "Content-Type" -> contentType
            )),
            body = HttpEntity.Streamed(
              response.bodyAsSource,
              response.header("Content-Length").map(_.toLong),
              Some(contentType)
            )
          )
        } else {
          logger.error(s"Failed to download file $file: ${response.statusText}")
          // Handle error cases
          Status(response.status)(s"Error: ${response.statusText}")
        }
      }
      .recover {case ex => 
      logger.error(s"Error downloading file: $file", ex)
      InternalServerError("Failed to download file")
      }
  }

  def upload = Action(parse.multipartFormData) { implicit request =>
    request.body.file("file").map { file =>
      val filename = Paths.get(file.filename).getFileName
      val dataFolder = config.get[String]("data_folder")
      val filePath = new java.io.File(s"$dataFolder/$filename")

      file.ref.copyTo(filePath)

      Redirect(routes.HomeController.add()).flashing("success" -> "Added CV to the database.")
    }.getOrElse {
      Redirect(routes.HomeController.add()).flashing("error" -> "Adding CV to database failed.")
    }
  }

  def delete(file: String) = Action.async { implicit request =>
    ws.url(s"${config.get[String]("server_url")}/delete")
      .withRequestTimeout(5.minutes)
      .post(Json.obj("filename" -> file))
      .map { response =>
        val deleteCount = (response.json.as[JsObject] \ "count").as[Int]
        Redirect(routes.HomeController.add())
          .flashing("success" -> s"File ${file} has been deleted (${deleteCount} chunks in total).")
      }
  }

  def feedback() = Action { implicit request: Request[AnyContent] =>
    Ok(Json.obj())
  }
}
