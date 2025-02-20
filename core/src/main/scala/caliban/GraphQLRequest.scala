package caliban

import caliban.GraphQLRequest.{ `apollo-federation-include-trace`, ftv1 }
import caliban.Value.StringValue
import caliban.interop.circe.{ IsCirceDecoder, IsCirceEncoder }
import caliban.interop.jsoniter.IsJsoniterCodec
import caliban.interop.play.{ IsPlayJsonReads, IsPlayJsonWrites }
import caliban.interop.tapir.IsTapirSchema
import caliban.interop.zio.IsZIOJsonCodec

/**
 * Represents a GraphQL request, containing a query, an operation name and a map of variables.
 */
case class GraphQLRequest(
  query: Option[String] = None,
  operationName: Option[String] = None,
  variables: Option[Map[String, InputValue]] = None,
  extensions: Option[Map[String, InputValue]] = None
) {

  def withExtension(key: String, value: InputValue): GraphQLRequest =
    copy(extensions = Some(extensions.foldLeft(Map(key -> value))(_ ++ _)))

  def withFederatedTracing: GraphQLRequest =
    withExtension(`apollo-federation-include-trace`, StringValue(ftv1))

  private[caliban] def isEmpty: Boolean =
    operationName.isEmpty && query.isEmpty && extensions.isEmpty

}

object GraphQLRequest {
  implicit def circeDecoder[F[_]: IsCirceDecoder]: F[GraphQLRequest]     =
    caliban.interop.circe.json.GraphQLRequestCirce.graphQLRequestDecoder.asInstanceOf[F[GraphQLRequest]]
  implicit def circeEncoder[F[_]: IsCirceEncoder]: F[GraphQLRequest]     =
    caliban.interop.circe.json.GraphQLRequestCirce.graphQLRequestEncoder.asInstanceOf[F[GraphQLRequest]]
  implicit def zioJsonCodec[F[_]: IsZIOJsonCodec]: F[GraphQLRequest]     =
    caliban.interop.zio.GraphQLRequestZioJson.graphQLRequestCodec.asInstanceOf[F[GraphQLRequest]]
  implicit def tapirSchema[F[_]: IsTapirSchema]: F[GraphQLRequest]       =
    caliban.interop.tapir.schema.requestSchema.asInstanceOf[F[GraphQLRequest]]
  implicit def jsoniterCodec[F[_]: IsJsoniterCodec]: F[GraphQLRequest]   =
    caliban.interop.jsoniter.GraphQLRequestJsoniter.graphQLRequestCodec.asInstanceOf[F[GraphQLRequest]]
  implicit def playJsonReads[F[_]: IsPlayJsonReads]: F[GraphQLRequest]   =
    caliban.interop.play.json.GraphQLRequestPlayJson.graphQLRequestReads.asInstanceOf[F[GraphQLRequest]]
  implicit def playJsonWrites[F[_]: IsPlayJsonWrites]: F[GraphQLRequest] =
    caliban.interop.play.json.GraphQLRequestPlayJson.graphQLRequestWrites.asInstanceOf[F[GraphQLRequest]]

  private[caliban] final val ftv1                              = "ftv1"
  private[caliban] final val `apollo-federation-include-trace` = "apollo-federation-include-trace"
}
