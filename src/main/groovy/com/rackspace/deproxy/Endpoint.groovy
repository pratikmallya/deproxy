package com.rackspace.deproxy

import groovy.util.logging.Log4j

import java.text.SimpleDateFormat

/**
 * A class that acts as a mock HTTP server.
 */

@Log4j
class Endpoint {

    String name
    String hostname
    Closure defaultHandler
    ServerConnector serverConnector

    protected Deproxy deproxy;


    public Endpoint(Map params, Deproxy deproxy) {
        this(
                deproxy,
                params?.port as Integer,
                params?.name as String,
                params?.hostname as String,
                params?.defaultHandler as Closure,
                params?.connectorFactory as Closure<ServerConnector>
        );
    }
    public Endpoint(Deproxy deproxy, Integer port=null, String name=null,
                           String hostname=null, Closure defaultHandler=null,
                           Closure<ServerConnector> connectorFactory=null) {

        if (deproxy == null) throw new IllegalArgumentException()

        if (name == null) name = "Endpoint-${System.currentTimeMillis()}"
        if (hostname == null) hostname = "localhost"

        this.deproxy = deproxy
        this.name = name
        this.hostname = hostname
        this.defaultHandler = defaultHandler

        if (connectorFactory) {
            this.serverConnector = connectorFactory(this);
        } else {
            this.serverConnector = new SocketServerConnector(this, port)
        }
    }

    void shutdown() {
        log.debug("Shutting down ${this.name}")
        serverConnector.shutdown()
        log.debug("Finished shutting down ${this.name}")
    }

    ResponseWithContext handleRequest(Request request, String connectionName) {

        log.debug "Begin handleRequest"

        try {

            MessageChain messageChain = null

            def requestId = request.headers.getFirstValue(Deproxy.REQUEST_ID_HEADER_NAME)
            if (requestId) {

                log.debug "the request has a request id: ${Deproxy.REQUEST_ID_HEADER_NAME}=${requestId}"

                messageChain = this.deproxy.getMessageChain(requestId)

            } else {

                log.debug "the request does not have a request id"
            }

            // Handler resolution:
            // 1. Check the handlers mapping specified to ``makeRequest``
            //   a. By reference
            //   b. By name
            // 2. Check the defaultHandler specified to ``makeRequest``
            // 3. Check the default for this endpoint
            // 4. Check the default for the parent Deproxy
            // 5. Fallback to simpleHandler

            def handler
            if (messageChain &&
                    messageChain.handlers &&
                    messageChain.handlers.containsKey(this)) {

                handler = messageChain.handlers[this]

            } else if (messageChain &&
                    messageChain.handlers &&
                    messageChain.handlers.containsKey(this.name)) {

                handler = messageChain.handlers[this.name]

            } else if (messageChain && messageChain.defaultHandler) {

                handler = messageChain.defaultHandler

            } else if (this.defaultHandler) {

                handler = this.defaultHandler

            } else if (this.deproxy.defaultHandler) {

                handler = this.deproxy.defaultHandler

            } else {

                handler = Handlers.&simpleHandler

            }

            log.debug "calling handler"
            Response response
            HandlerContext context = new HandlerContext()

            if (handler instanceof Closure) {
                if (handler.getMaximumNumberOfParameters() == 1) {

                    response = handler(request)

                } else if (handler.getMaximumNumberOfParameters() == 2) {

                    response = handler(request, context)

                } else {

                    throw new UnsupportedOperationException("Invalid number of parameters in handler")
                }

            } else {

                response = handler(request)
            }

            log.debug "returned from handler"


            if (context.sendDefaultResponseHeaders) {

                if (!response.headers.contains("Server")) {
                    response.headers.add("Server", Deproxy.VERSION_STRING)
                }
                if (!response.headers.contains("Date")) {
                    response.headers.add("Date", datetimeString())
                }

                if (response.body) {

                    if (context.usedChunkedTransferEncoding) {

                        if (!response.headers.contains("Transfer-Encoding")) {
                            response.headers.add("Transfer-Encoding", "chunked")
                        }

                    } else if (!response.headers.contains("Transfer-Encoding") ||
                            response.headers["Transfer-Encoding"] == "identity") {

                        def length
                        String contentType
                        if (response.body instanceof String) {
                            length = response.body.length()
                            contentType = "text/plain"
                        } else if (response.body instanceof byte[]) {
                            length = response.body.length
                            contentType = "application/octet-stream"
                        } else {
                            throw new UnsupportedOperationException("Unknown data type in requestBody")
                        }

                        if (length > 0) {
                            if (!response.headers.contains("Content-Length")) {
                                response.headers.add("Content-Length", length)
                            }
                            if (!response.headers.contains("Content-Type")) {
                                response.headers.add("Content-Type", contentType)
                            }
                        }
                    }
                }

                if (!response.headers.contains("Content-Length") &&
                        !response.headers.contains("Transfer-Encoding")) {

                    response.headers.add("Content-Length", 0)
                }
            }

            if (requestId && !response.headers.contains(Deproxy.REQUEST_ID_HEADER_NAME)) {
                response.headers.add(Deproxy.REQUEST_ID_HEADER_NAME, requestId)
            }

            def handling = new Handling(this, request, response, connectionName)
            if (messageChain) {
                messageChain.addHandling(handling)
            } else {
                this.deproxy.addOrphanedHandling(handling)
            }

            return new ResponseWithContext(response: response, context:context)

        } finally {

        }
    }

    String datetimeString() {
        // Return the current date and time formatted for a message header.

        Calendar calendar = Calendar.getInstance();
        SimpleDateFormat dateFormat = new SimpleDateFormat(
                "EEE, dd MMM yyyy HH:mm:ss z", Locale.US);
        dateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        return dateFormat.format(calendar.getTime());
    }

}
