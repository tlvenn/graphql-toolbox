package controllers

import javax.inject.Inject

import akka.actor.ActorSystem
import play.api.libs.json.{JsObject, JsString, JsPath, Json}
import play.api.mvc._
import play.api.Configuration
import sangria.execution.{ErrorWithResolver, QueryAnalysisError, Executor}

import sangria.parser.{SyntaxError, QueryParser}
import sangria.marshalling.playJson._

import sangria.renderer.QueryRenderer
import sangria.schema.{SchemaMaterializationException, Schema}

import scala.concurrent.Future
import scala.util.{Failure, Success}

class Application @Inject()(system: ActorSystem, config: Configuration) extends Controller {
  import system.dispatcher

  val gaCode = config.getString("gaCode")

  def index = Action {
    Ok(views.html.index(gaCode))
  }

  def format = Action {
    Ok(views.html.format(gaCode))
  }

  def proxy = Action {
    Ok(views.html.proxy(gaCode))
  }

  def graphqlProxy = Action.async(parse.json) { request =>
    val query = (request.body \ "query").as[String]
    val schema = (request.body \ "schema").as[String]
    val operation = (request.body \ "operationName").asOpt[String]

    val variables = (request.body \ "variables").toOption.flatMap {
      case JsString(vars) => Some(parseVariables(vars))
      case obj: JsObject => Some(obj)
      case _ => None
    }

    materializeSchema(schema) match {
      case Right(schema) =>
        executeQuery(schema, query, variables, operation)
      case Left(result) =>
        Future.successful(result)
    }

  }

  private def materializeSchema(schemaDef: String): Either[Result, Schema[Any, Any]] = {
    QueryParser.parse(schemaDef) match {
      case Success(schemaAst) =>
        try {
          Right(Schema.buildFromAst(schemaAst))
        } catch {
          case e: SchemaMaterializationException =>
            Left(BadRequest(Json.obj("materiamlizationError" -> e.getMessage)))
        }
      case Failure(error: SyntaxError) =>
        Left(BadRequest(Json.obj(
          "syntaxError" -> error.getMessage,
          "locations" -> Json.arr(Json.obj(
            "line" -> error.originalError.position.line,
            "column" -> error.originalError.position.column)))))
      case Failure(error) =>
        throw error
    }
  }

  private def parseVariables(variables: String) =
    if (variables.trim == "" || variables.trim == "null") Json.obj() else Json.parse(variables).as[JsObject]

  private def executeQuery(schema: Schema[Any, Any], query: String, variables: Option[JsObject], operation: Option[String]) =
    QueryParser.parse(query) match {

      // query parsed successfully, time to execute it!
      case Success(queryAst) =>
        Executor.execute(schema, queryAst,
          operationName = operation,
          variables = variables getOrElse Json.obj(),
          maxQueryDepth = Some(15))
            .map(Ok(_))
            .recover {
              case error: QueryAnalysisError ⇒ BadRequest(error.resolveError)
              case error: ErrorWithResolver ⇒ InternalServerError(error.resolveError)
            }

      case Failure(error: SyntaxError) =>
        Future.successful(BadRequest(Json.obj(
          "syntaxError" -> error.getMessage,
          "locations" -> Json.arr(Json.obj(
            "line" -> error.originalError.position.line,
            "column" -> error.originalError.position.column)))))

      case Failure(error) =>
        throw error
    }

  def formatGet(query: String) = Action.async { request =>
    formatQuery(query)
  }

  def formatPost = Action.async(parse.text) { request =>
    formatQuery(request.body)
  }

  private def formatQuery(query: String) = {
    QueryParser.parse(query) match {
      case Success(ast) =>
        Future.successful(Ok(QueryRenderer.render(ast)))
      case Failure(error) =>
        Future.successful(BadRequest(error.getMessage))

    }
  }
}
