package caliban.wrappers

import caliban.CalibanError.{ ExecutionError, ValidationError }
import caliban.InputValue.ObjectValue
import caliban.Macros.gqldoc
import caliban.TestUtils._
import caliban.Value.{ IntValue, StringValue }
import caliban._
import caliban.execution.{ ExecutionRequest, FieldInfo }
import caliban.introspection.adt.{ __Directive, __DirectiveLocation }
import caliban.parsing.adt.{ Directive, Document, LocationInfo }
import caliban.schema.Annotations.GQLDirective
import caliban.schema.Schema.auto._
import caliban.schema.{ ArgBuilder, GenericSchema, Schema }
import caliban.wrappers.ApolloPersistedQueries.ApolloPersistence
import caliban.wrappers.Wrapper.{ CombinedWrapper, ExecutionWrapper, FieldWrapper, ValidationWrapper }
import caliban.wrappers.Wrappers._
import io.circe.syntax._
import zio._
import zio.query.ZQuery
import zio.test._

import scala.language.postfixOps

object WrappersSpec extends ZIOSpecDefault {

  override def spec =
    suite("WrappersSpec")(
      test("wrapPureValues false") {
        case class Test(a: Int, b: UIO[Int])
        for {
          ref         <- Ref.make[Int](0)
          wrapper      = new FieldWrapper[Any](false) {
                           def wrap[R1 <: Any](
                             query: ZQuery[R1, ExecutionError, ResponseValue],
                             info: FieldInfo
                           ): ZQuery[R1, ExecutionError, ResponseValue] =
                             ZQuery.fromZIO(ref.update(_ + 1)) *> query
                         }
          interpreter <- (graphQL(RootResolver(Test(1, ZIO.succeed(2)))) @@ wrapper).interpreter.orDie
          query        = gqldoc("""{ a b }""")
          _           <- interpreter.execute(query)
          counter     <- ref.get
        } yield assertTrue(counter == 1)
      },
      test("wrapPureValues true") {
        case class Test(a: Int, b: UIO[Int])
        for {
          ref         <- Ref.make[Int](0)
          wrapper      = new FieldWrapper[Any](true) {
                           def wrap[R1 <: Any](
                             query: ZQuery[R1, ExecutionError, ResponseValue],
                             info: FieldInfo
                           ): ZQuery[R1, ExecutionError, ResponseValue] =
                             ZQuery.fromZIO(ref.update(_ + 1)) *> query
                         }
          interpreter <- (graphQL(RootResolver(Test(1, ZIO.succeed(2)))) @@ wrapper).interpreter.orDie
          query        = gqldoc("""{ a b }""")
          _           <- interpreter.execute(query)
          counter     <- ref.get
        } yield assertTrue(counter == 2)
      },
      // i2161
      test("wrapPureValues true and false") {
        case class Obj1(a1: List[Obj2])
        case class Obj2(a2: Int)
        case class Test(a0: Obj1, b: UIO[Int])
        for {
          ref1        <- Ref.make[Int](0)
          wrapper1     = new FieldWrapper[Any](true) {
                           def wrap[R1 <: Any](
                             query: ZQuery[R1, ExecutionError, ResponseValue],
                             info: FieldInfo
                           ): ZQuery[R1, ExecutionError, ResponseValue] =
                             ZQuery.fromZIO(ref1.update(_ + 1)) *> query
                         }
          ref2        <- Ref.make[Int](0)
          wrapper2     = new FieldWrapper[Any](false) {
                           def wrap[R1 <: Any](
                             query: ZQuery[R1, ExecutionError, ResponseValue],
                             info: FieldInfo
                           ): ZQuery[R1, ExecutionError, ResponseValue] =
                             ZQuery.fromZIO(ref2.update(_ + 1)) *> query
                         }
          interpreter <-
            (graphQL(RootResolver(Test(Obj1(List(Obj2(1))), ZIO.succeed(2)))) @@ wrapper1 @@ wrapper2).interpreter.orDie
          query        = gqldoc("""{ a0 { a1 { a2 } } b }""")
          _           <- interpreter.execute(query)
          counter1    <- ref1.get
          counter2    <- ref2.get
        } yield assertTrue(counter1 == 4, counter2 == 1)
      },
      test("Failures in FieldWrapper have a path and location") {
        case class Query(a: A)
        case class A(b: B)
        case class B(c: Int)

        val wrapper = new FieldWrapper[Any](true) {
          def wrap[R1 <: Any](
            query: ZQuery[R1, ExecutionError, ResponseValue],
            info: FieldInfo
          ): ZQuery[R1, ExecutionError, ResponseValue] =
            if (info.name == "c") ZQuery.fail(ExecutionError("error"))
            else query
        }
        for {
          interpreter <- (graphQL(RootResolver(Query(A(B(1))))) @@ wrapper).interpreter.orDie
          query        = gqldoc("""{ a { b { c } } }""")
          result      <- interpreter.execute(query)
          firstError   = result.errors.head.asInstanceOf[ExecutionError]
        } yield assertTrue(
          firstError.path.mkString(",") == """"a","b","c"""",
          firstError.locationInfo.contains(LocationInfo(11, 1))
        )
      },
      test("Max fields") {
        case class A(b: B)
        case class B(c: Int)
        case class Test(a: A)
        val interpreter = (graphQL(RootResolver(Test(A(B(2))))) @@ maxFields(2)).interpreter
        val query       = gqldoc("""
              {
                a {
                  b {
                    c
                  }
                }
              }""")
        interpreter.flatMap(_.execute(query)).map { response =>
          assertTrue(response.errors == List(ValidationError("Query has too many fields: 3. Max fields: 2.", "")))
        }
      },
      test("Max fields with fragment") {
        case class A(b: B)
        case class B(c: Int)
        case class Test(a: A)
        val interpreter = (graphQL(RootResolver(Test(A(B(2))))) @@ maxFields(2)).interpreter
        val query       = gqldoc("""
              query test {
                a {
                  ...f
                }
              }

              fragment f on A {
                b {
                  c
                }
              }
              """)
        interpreter.flatMap(_.execute(query)).map { response =>
          assertTrue(response.errors == List(ValidationError("Query has too many fields: 3. Max fields: 2.", "")))
        }
      },
      test("Max depth") {
        case class A(b: B)
        case class B(c: Int)
        case class Test(a: A)
        val interpreter = (graphQL(RootResolver(Test(A(B(2))))) @@ maxDepth(2)).interpreter
        val query       = gqldoc("""
              {
                a {
                  b {
                    c
                  }
                }
              }""")
        interpreter.flatMap(_.execute(query)).map { response =>
          assertTrue(response.errors == List(ValidationError("Query is too deep: 3. Max depth: 2.", "")))
        }
      },
      test("Timeout") {
        case class Test(a: UIO[Int])

        object schema extends GenericSchema[Any] {
          val interpreter =
            (graphQL(RootResolver(Test(Clock.sleep(2 minutes).as(0)))) @@
              timeout(1 minute)).interpreter
        }

        val query = gqldoc("""
              {
                a
              }""")
        for {
          fiber <- schema.interpreter.flatMap(_.execute(query)).map(_.errors).fork
          _     <- TestClock.adjust(1 minute)
          res   <- fiber.join
        } yield assertTrue(res == List(ExecutionError("""Query was interrupted after timeout of 1 m:

              {
                a
              }""".stripMargin)))
      },
      suite("Apollo Tracing") {
        case class Query(hero: Hero)
        case class Hero(name: UIO[String], friends: List[Hero] = Nil, bestFriend: Option[Hero] = None)

        object schema extends GenericSchema[Any] {
          implicit lazy val heroSchema: Schema[Any, Hero] = gen

          def api(latch: Promise[Nothing, Unit], excludePureFields: Boolean) =
            graphQL(
              RootResolver(
                Query(
                  Hero(
                    latch.succeed(()) *> ZIO.sleep(1 second).as("R2-D2"),
                    List(
                      Hero(ZIO.sleep(2 second).as("Luke Skywalker")),
                      Hero(ZIO.sleep(3 second).as("Han Solo")),
                      Hero(ZIO.sleep(4 second).as("Leia Organa"))
                    )
                  )
                )
              )
            ) @@ ApolloTracing.apolloTracing(excludePureFields)
        }

        val query = gqldoc("""
              {
                hero {
                  name
                  friends {
                    name
                  }
                  bestFriend {
                    name
                  }
                }
              }""")

        def test_(excludePureFields: Boolean) =
          for {
            latch       <- Promise.make[Nothing, Unit]
            interpreter <- schema.api(latch, excludePureFields).interpreter
            fiber       <- interpreter.execute(query).map(_.extensions.map(_.toString)).fork
            _           <- latch.await
            _           <- TestClock.adjust(4 seconds)
            result      <- fiber.join.map(_.getOrElse("null"))
          } yield result

        List(
          test("excludePureFields = false") {
            test_(false).map { res =>
              assertTrue(
                res == """{"tracing":{"version":1,"startTime":"1970-01-01T00:00:00.000Z","endTime":"1970-01-01T00:00:04.000Z","duration":4000000000,"parsing":{"startOffset":0,"duration":0},"validation":{"startOffset":0,"duration":0},"execution":{"resolvers":[{"path":["hero","bestFriend"],"parentType":"Hero","fieldName":"bestFriend","returnType":"Hero","startOffset":0,"duration":0},{"path":["hero","name"],"parentType":"Hero","fieldName":"name","returnType":"String!","startOffset":0,"duration":1000000000},{"path":["hero","friends",0,"name"],"parentType":"Hero","fieldName":"name","returnType":"String!","startOffset":0,"duration":2000000000},{"path":["hero","friends",1,"name"],"parentType":"Hero","fieldName":"name","returnType":"String!","startOffset":0,"duration":3000000000},{"path":["hero"],"parentType":"Query","fieldName":"hero","returnType":"Hero!","startOffset":0,"duration":4000000000},{"path":["hero","friends"],"parentType":"Hero","fieldName":"friends","returnType":"[Hero!]!","startOffset":0,"duration":4000000000},{"path":["hero","friends",2,"name"],"parentType":"Hero","fieldName":"name","returnType":"String!","startOffset":0,"duration":4000000000}]}}}"""
              )
            }
          },
          test("excludePureFields = true") {
            test_(true).map { res =>
              assertTrue(
                res == """{"tracing":{"version":1,"startTime":"1970-01-01T00:00:00.000Z","endTime":"1970-01-01T00:00:04.000Z","duration":4000000000,"parsing":{"startOffset":0,"duration":0},"validation":{"startOffset":0,"duration":0},"execution":{"resolvers":[{"path":["hero","name"],"parentType":"Hero","fieldName":"name","returnType":"String!","startOffset":0,"duration":1000000000},{"path":["hero","friends",0,"name"],"parentType":"Hero","fieldName":"name","returnType":"String!","startOffset":0,"duration":2000000000},{"path":["hero","friends",1,"name"],"parentType":"Hero","fieldName":"name","returnType":"String!","startOffset":0,"duration":3000000000},{"path":["hero"],"parentType":"Query","fieldName":"hero","returnType":"Hero!","startOffset":0,"duration":4000000000},{"path":["hero","friends"],"parentType":"Hero","fieldName":"friends","returnType":"[Hero!]!","startOffset":0,"duration":4000000000},{"path":["hero","friends",2,"name"],"parentType":"Hero","fieldName":"name","returnType":"String!","startOffset":0,"duration":4000000000}]}}}"""
              )
            }
          },
          test("enabled") {
            for {
              r1 <- ZIO.scoped(ApolloTracing.enabled(false) *> test_(false))
              r2 <- test_(false)
            } yield assertTrue(r1 == "null", r2 != "null")
          },
          test("enabledWith") {
            for {
              r1 <- ApolloTracing.enabledWith(value = false)(test_(false))
              r2 <- test_(false)
            } yield assertTrue(r1 == "null", r2 != "null")
          }
        )
      },
      suite("Apollo Persisted Queries")({
        def mockWrapper[R](fail: Ref[Boolean]): ValidationWrapper[R] = new ValidationWrapper[R] {
          override def wrap[R1 <: R](
            f: Document => ZIO[R1, ValidationError, ExecutionRequest]
          ): Document => ZIO[R1, ValidationError, ExecutionRequest] =
            (doc: Document) =>
              f(doc) <* {
                ZIO.unlessZIO(Configurator.skipValidation) {
                  ZIO.whenZIO(fail.get)(ZIO.fail(ValidationError("boom", "boom")))
                }
              }
        }

        val extensions = Some(
          Map(
            "persistedQuery" -> ObjectValue(
              Map("sha256Hash" -> StringValue("e005c1d727f7776a57a661d61a182816d8953c0432780beeae35e337830b1746"))
            )
          )
        )
        List(
          test("non-APQ queries are processed normally") {
            case class Test(test: String)

            (for {
              interpreter <-
                (graphQL(RootResolver(Test("ok"))) @@ ApolloPersistedQueries.wrapper).interpreter
              result      <- interpreter.executeRequest(GraphQLRequest(query = Some("{test}")))
            } yield assertTrue(result.asJson.noSpaces == """{"data":{"test":"ok"}}"""))
          },
          test("hash not found") {
            case class Test(test: String)
            val interpreter = (graphQL(RootResolver(Test("ok"))) @@ ApolloPersistedQueries.wrapper).interpreter
            interpreter
              .flatMap(
                _.executeRequest(
                  GraphQLRequest(extensions =
                    Some(Map("persistedQuery" -> ObjectValue(Map("sha256Hash" -> StringValue("my-hash")))))
                  )
                )
              )
              .map { response =>
                assertTrue(
                  response.asJson.noSpaces == """{"data":null,"errors":[{"message":"PersistedQueryNotFound"}]}"""
                )
              }
          },
          test("cache poisoning") {
            case class Test(test: String, malicious: String)

            (for {
              interpreter <-
                (graphQL(RootResolver(Test("ok", "malicious"))) @@ ApolloPersistedQueries.wrapper).interpreter
              // The hash for the query "{test}"  attempting to poison the cache by passing in a different query
              r1          <- interpreter.executeRequest(GraphQLRequest(query = Some("{malicious}"), extensions = extensions))
              r2          <- interpreter.executeRequest(GraphQLRequest(extensions = extensions))
            } yield assertTrue(
              r1.asJson.noSpaces == """{"data":null,"errors":[{"message":"Provided sha does not match any query"}]}"""
            ) && assertTrue(r2.asJson.noSpaces == """{"data":null,"errors":[{"message":"PersistedQueryNotFound"}]}"""))
          },
          test("hash found") {
            case class Test(test: String)

            (for {
              interpreter <- (graphQL(RootResolver(Test("ok"))) @@ ApolloPersistedQueries.wrapper).interpreter
              _           <- interpreter.executeRequest(GraphQLRequest(query = Some("{test}"), extensions = extensions))
              result      <- interpreter.executeRequest(GraphQLRequest(extensions = extensions))
            } yield assertTrue(result.asJson.noSpaces == """{"data":{"test":"ok"}}"""))
          },
          test("executes first") {
            case class Test(test: String)

            (for {
              shouldFail  <- Ref.make(false)
              interpreter <-
                (graphQL(RootResolver(Test("ok"))) @@
                  mockWrapper(shouldFail) @@ ApolloPersistedQueries.wrapper @@ mockWrapper(shouldFail)).interpreter
              _           <- interpreter.executeRequest(GraphQLRequest(query = Some("{test}"), extensions = extensions))
              _           <- shouldFail.set(true)
              result      <- interpreter.executeRequest(GraphQLRequest(extensions = extensions))
            } yield assertTrue(result.asJson.noSpaces == """{"data":{"test":"ok"}}"""))
          },
          test("does not register successful validation if another validation wrapper fails") {
            case class Test(test: String)

            (for {
              shouldFail  <- Ref.make(true)
              interpreter <-
                (graphQL(RootResolver(Test("ok"))) @@
                  mockWrapper(shouldFail) @@ ApolloPersistedQueries.wrapper @@ mockWrapper(shouldFail)).interpreter
              first       <- interpreter.executeRequest(GraphQLRequest(query = Some("{test}"), extensions = extensions))
              second      <- interpreter.executeRequest(GraphQLRequest(extensions = extensions))
            } yield {
              val expected = """{"data":null,"errors":[{"message":"boom"}]}"""
              assertTrue(first.asJson.noSpaces == expected) && assertTrue(
                second.asJson.noSpaces == """{"data":null,"errors":[{"message":"PersistedQueryNotFound"}]}"""
              )
            })
          },
          test("invalid / missing variables in cached query") {
            case class TestInput(testField: String)
            case class Test(test: TestInput => String)
            implicit val testInputArg: ArgBuilder[TestInput] = ArgBuilder.gen
            implicit val testSchema: Schema[Any, Test]       = Schema.gen

            val extensions = Some(
              Map(
                "persistedQuery" -> ObjectValue(
                  Map("sha256Hash" -> StringValue("c85ff5936156aafeafa5641b2ce05492316127cfcb0a18b5164e02cc7edb0316"))
                )
              )
            )

            val query          = gqldoc("""query TestQuery($testField: String!) { test(testField: $testField) }""")
            val validVariables = Map("testField" -> StringValue("foo"))
            val invalidTypeVar = Map("testField" -> IntValue(42))
            val missingVar     = Map("testField2" -> StringValue("foo"))

            (for {
              interpreter         <-
                (graphQL(RootResolver(Test(_.testField))) @@ ApolloPersistedQueries.wrapper).interpreter
              validTest           <-
                interpreter.executeRequest(
                  GraphQLRequest(query = Some(query), variables = Some(validVariables), extensions = extensions)
                )
              invalidTypeTest     <-
                interpreter.executeRequest(GraphQLRequest(variables = Some(invalidTypeVar), extensions = extensions))
              missingVariableTest <-
                interpreter.executeRequest(GraphQLRequest(variables = Some(missingVar), extensions = extensions))
            } yield assertTrue(validTest.asJson.noSpaces == """{"data":{"test":"foo"}}""") &&
              assertTrue(
                invalidTypeTest.asJson.noSpaces == """{"data":null,"errors":[{"message":"Variable 'testField' with value 42 cannot be coerced into String."}]}"""
              ) &&
              assertTrue(
                missingVariableTest.asJson.noSpaces == """{"data":null,"errors":[{"message":"Variable 'testField' is null but is specified to be non-null."}]}"""
              ))
          }
        )
      }),
      test("custom query directive") {
        val customWrapper        = new ExecutionWrapper[Any] {
          def wrap[R1 <: Any](
            f: ExecutionRequest => ZIO[R1, Nothing, GraphQLResponse[CalibanError]]
          ): ExecutionRequest => ZIO[R1, Nothing, GraphQLResponse[CalibanError]] =
            request =>
              if (request.field.directives.exists(_.name == "customQueryDirective")) {
                ZIO.succeed {
                  GraphQLResponse(Value.BooleanValue(true), Nil)
                }
              } else f(request)
        }
        val customQueryDirective = __Directive(
          "customQueryDirective",
          None,
          Set(
            __DirectiveLocation.QUERY
          ),
          _ => Nil,
          isRepeatable = false
        )
        val interpreter          = (graphQL(
          resolver,
          List(
            customQueryDirective
          )
        ) @@ customWrapper).interpreter
        val query                = gqldoc("""
            query @customQueryDirective {
              characters {
                name
              }
            }""")
        interpreter.flatMap(_.execute(query)).map { response =>
          assertTrue(response.data.toString == """true""")
        }
      },
      test("skipping validation wrappers for introspection") {
        case class Test(test: String)
        val gql = graphQL(RootResolver(Test("ok")))

        for {
          interpreter <- (gql @@ (maxFields(5).skipForIntrospection |+| maxDepth(1).skipForIntrospection)).interpreter
          result      <- interpreter.executeRequest(GraphQLRequest(query = Some(TestUtils.introspectionQuery)))
        } yield assertTrue(result.asJson.hcursor.downField("errors").failed)
      },
      test("check directives") {
        // setup the annotation
        val directiveName = "requiredRole"
        val attributeName = "role"
        case class RequiredRole(role: String)
            extends GQLDirective(Directive(directiveName, Map(attributeName -> StringValue(role))))

        // declare the schema
        case class SomeObject(@RequiredRole("admin") a: UIO[String])
        case class Query(o: SomeObject)

        // create our wrapper that checks directive and fail if the role is not the expected one
        case class Context(role: String)
        val wrapper = Wrappers.checkDirectives(directives =>
          for {
            currentRole   <- ZIO.serviceWith[Context](_.role)
            restrictedRole = directives.find(_.name == directiveName).flatMap(_.arguments.get(attributeName)).flatMap {
                               case StringValue(role) => Some(role)
                               case _                 => None
                             }
            _             <- ZIO.unless(restrictedRole.forall(_ == currentRole))(ZIO.fail(ExecutionError("Unauthorized")))
          } yield ()
        )

        val gql = graphQL(RootResolver(Query(SomeObject(ZIO.succeed("a"))))) @@ wrapper

        for {
          interpreter <- gql.interpreter
          req          = GraphQLRequest(query = Some("{ o { a } }"))
          // we have the required role, it should succeed
          result      <- interpreter.executeRequest(req).provide(ZLayer.succeed(Context("admin")))
          // we don't have the required role, it should fail
          result2     <- interpreter.executeRequest(req).provide(ZLayer.succeed(Context("user")))
        } yield assertTrue(
          result.asJson.hcursor.downField("errors").failed,
          result2.asJson.hcursor.downField("errors").succeeded
        )
      },
      suite("Empty wrapper")(
        test("is not combined with other wrappers") {
          List(
            Wrapper.empty |+| maxFields(10) |+| Wrapper.empty,
            Wrapper.empty |+| maxFields(10),
            maxFields(10) |+| Wrapper.empty
          ).foldLeft(assertCompletes) { case (result, wrapper) =>
            (wrapper match {
              case CombinedWrapper(_) => assertNever("Empty wrapper should not be combined")
              case _                  => assertCompletes
            }) && result
          }
        },
        test("is ignored when used as an aspect") {
          case class Test(test: String)
          val gql = graphQL(RootResolver(Test("ok")))

          assertTrue(gql == gql @@ Wrapper.empty)
        }
      )
    )
}
