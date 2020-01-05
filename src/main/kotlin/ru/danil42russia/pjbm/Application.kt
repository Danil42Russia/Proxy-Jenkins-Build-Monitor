package ru.danil42russia.pjbm

import io.ktor.application.Application
import io.ktor.application.call
import io.ktor.client.HttpClient
import io.ktor.client.call.call
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.features.auth.Auth
import io.ktor.client.features.auth.providers.basic
import io.ktor.client.features.cookies.AcceptAllCookiesStorage
import io.ktor.client.features.cookies.HttpCookies
import io.ktor.client.features.logging.DEFAULT
import io.ktor.client.features.logging.LogLevel
import io.ktor.client.features.logging.Logger
import io.ktor.client.features.logging.Logging
import io.ktor.client.response.readBytes
import io.ktor.client.response.readText
import io.ktor.content.TextContent
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.contentType
import io.ktor.request.header
import io.ktor.request.receiveMultipart
import io.ktor.request.uri
import io.ktor.response.respondBytes
import io.ktor.response.respondRedirect
import io.ktor.response.respondText
import io.ktor.routing.get
import io.ktor.routing.post
import io.ktor.routing.routing
import io.ktor.server.engine.commandLineEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.Jetty
import kotlinx.coroutines.runBlocking
import kotlinx.html.*
import kotlinx.html.stream.createHTML

fun main(args: Array<String>) {
    embeddedServer(Jetty, commandLineEnvironment(args)).start(wait = true)
}

suspend fun getClient(jenkinsUrl: String, jenkinsLogin: String, jenkinsPassword: String): HttpClient {
    // https://github.com/ktorio/ktor/issues/1066
    val client = HttpClient(OkHttp) {
        install(Auth) {
            basic {
                username = jenkinsLogin
                password = jenkinsPassword
                sendWithoutRequest = true
            }
        }

        install(HttpCookies) {
            storage = AcceptAllCookiesStorage()
        }

        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.INFO
        }
    }

    client.call(jenkinsUrl) {
        method = HttpMethod.Get
    }.close()

    return client
}

fun Application.module() {
    var client = HttpClient()

    var jenkinsUrl = System.getenv("JENKINS_URL")
    var jenkinsMonitorUrl = System.getenv("JENKINS_MONITOR_URL")
    val jenkinsLogin = System.getenv("JENKINS_LOGIN")
    val jenkinsPassword = System.getenv("JENKINS_PASSWORD")

    runBlocking {
        client =
            getClient(
                jenkinsUrl,
                jenkinsLogin,
                jenkinsPassword
            )
    }

    routing {
        get("/monitor") {
            val response = client.call(jenkinsUrl + jenkinsMonitorUrl) {
                method = HttpMethod.Get
            }.response

            call.respondText(response.readText(), response.contentType(), response.status)
            response.close()
        }

        get("{...}") {
            val url = call.request.uri

            if (
                url.startsWith("/static/", 0) ||
                url.startsWith("/plugin/", 0) ||
                url.startsWith("/adjuncts/", 0) ||
                url.startsWith("/build-monitor-plugin/", 0)
            ) {

                val response = client.call(jenkinsUrl + url) {
                    method = HttpMethod.Get
                }.response

                if (response.status == HttpStatusCode.OK) {
                    when (val type = response.contentType()) {
                        ContentType.Image.PNG,
                        ContentType.Image.XIcon,
                        ContentType.Application.FontWoff,
                        ContentType.parse("application/x-font-woff") ->
                            call.respondBytes(response.readBytes(), type, response.status)
                        else ->
                            call.respondText(response.readText(), type, response.status)
                    }
                }
                response.close()
            }
        }

        post("/\$stapler/bound/*/fetchJobViews") {
            val response = client.call(jenkinsUrl + call.request.uri) {
                method = HttpMethod.Post

                headers.append("Crumb", call.request.header("Crumb")!!)
                headers.append(".crumb", call.request.header(".crumb")!!)

                body = TextContent("[]", ContentType.parse("application/x-stapler-method-invocation;charset=UTF-8"))
            }.response

            call.respondBytes(
                response.readBytes(),
                response.contentType(),
                response.status
            )
            response.close()
        }

        get("/admin") {
            val html = createHTML().html {
                body {
                    form("/admin/form", method = FormMethod.post, encType = FormEncType.multipartFormData) {
                        div {
                            p {
                                +"Jenkins URL: "
                                textInput(name = "jenkins_url") {
                                    value = jenkinsUrl
                                }
                            }
                            p {
                                +"Monitor Path: "
                                textInput(name = "monitor_path") {
                                    value = jenkinsMonitorUrl
                                }
                            }
                            p {
                                submitInput {
                                    value = "Send"
                                }
                            }
                        }
                    }
                }
            }

            call.respondText(html, ContentType.Text.Html, HttpStatusCode.OK)
        }

        post("/admin/form") {
            val multipart = call.receiveMultipart()

            multipart.forEachPart { part ->
                when (part) {
                    is PartData.FormItem -> {
                        if (part.name == "jenkins_url") {
                            jenkinsUrl = part.value
                        }

                        if (part.name == "monitor_path") {
                            jenkinsMonitorUrl = part.value
                        }
                    }
                }
            }

            client =
                getClient(
                    jenkinsUrl,
                    jenkinsLogin,
                    jenkinsPassword
                )

            call.respondRedirect("/admin")
        }
    }
}
