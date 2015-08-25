package munni.netty;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.Executors;
import java.util.Arrays;

import org.jboss.netty.util.CharsetUtil;
import org.jboss.netty.bootstrap.ClientBootstrap;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.socket.nio.NioClientSocketChannelFactory;
import org.jboss.netty.handler.codec.http.CookieEncoder;
import org.jboss.netty.handler.codec.http.DefaultHttpRequest;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpMethod;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpVersion;

import com.github.kristofa.brave.SpanId;
import com.github.kristofa.brave.Brave;
import com.github.kristofa.brave.SpanCollector;
import com.github.kristofa.brave.EndpointSubmitter;
import com.github.kristofa.brave.ClientTracer;
import com.github.kristofa.brave.ServerTracer;
import com.github.kristofa.brave.TraceFilter;
import com.github.kristofa.brave.FixedSampleRateTraceFilter;
import com.github.kristofa.brave.LoggingSpanCollector;

import com.twitter.zipkin.gen.Span;

import java.util.logging.Level;
import java.util.logging.Logger;

import static org.mockito.Mockito.mock;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;

/**
 * A simple HTTP client that prints out the content of the HTTP response to
 * {@link System#out} to test {@link HttpSnoopServer}.
 */
public class HttpSnoopClient {

    private final URI uri;
    private static final Logger mockLogger = mock(Logger.class);
    private static TraceFilter traceFilter = new FixedSampleRateTraceFilter(1);
    private static SpanCollector mockSpanCollector = mock(SpanCollector.class);
    private final EventBus bus = new EventBus("netty-async");

    public HttpSnoopClient(URI uri) {
        this.uri = uri;
        bus.register(this);
    }

    @Subscribe
    public void listen(Span span) {
        //listen to subscribes
        System.out.println("Got span ["+span+"]");
    }

    public void run() {
        String scheme = uri.getScheme() == null? "http" : uri.getScheme();
        String host = uri.getHost() == null? "localhost" : uri.getHost();
        int port = uri.getPort();
        if (port == -1) {
            if (scheme.equalsIgnoreCase("http")) {
                port = 80;
            } else if (scheme.equalsIgnoreCase("https")) {
                port = 443;
            }
        }

        if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
            System.err.println("Only HTTP(S) is supported.");
            return;
        }

        boolean ssl = scheme.equalsIgnoreCase("https");

        //do some tracing
        final ClientTracer clientTracer = Brave.getClientTracer(mockSpanCollector, Arrays.asList(traceFilter));
        SpanId spanId = clientTracer.startNewSpan("HELLO-1");
        Span span = Brave.getClientSpanThreadBinder().getCurrentClientSpan();

        // Configure the client.
        ClientBootstrap bootstrap = new ClientBootstrap(
                new NioClientSocketChannelFactory(
                        Executors.newCachedThreadPool(),
                        Executors.newCachedThreadPool()));

        // Set up the event pipeline factory.
        bootstrap.setPipelineFactory(new HttpSnoopClientPipelineFactory(ssl, span, bus));

        // Start the connection attempt.
        ChannelFuture future = bootstrap.connect(new InetSocketAddress(host, port));

        // Wait until the connection attempt succeeds or fails.
        Channel channel = future.awaitUninterruptibly().getChannel();
        if (!future.isSuccess()) {
            future.getCause().printStackTrace();
            bootstrap.releaseExternalResources();
            return;
        }



        // Prepare the HTTP request.
        HttpRequest request = new DefaultHttpRequest(
                HttpVersion.HTTP_1_1, HttpMethod.POST, uri.getRawPath());
		//request.setHeader(HttpHeaders.Names.CONTENT_TYPE, "text/xml; charset=UTF-8");
        request.setHeader(HttpHeaders.Names.HOST, host);
		request.setHeader(HttpHeaders.Names.CACHE_CONTROL, "no-cache");
		request.setHeader(HttpHeaders.Names.PRAGMA, "no-cache");
        request.setHeader(HttpHeaders.Names.CONNECTION, HttpHeaders.Values.CLOSE);
        request.setHeader(HttpHeaders.Names.ACCEPT_ENCODING, HttpHeaders.Values.GZIP);

        // Set some example cookies.
        CookieEncoder httpCookieEncoder = new CookieEncoder(false);
        httpCookieEncoder.addCookie("my-cookie", "foo");
        httpCookieEncoder.addCookie("another-cookie", "bar");
        request.setHeader(HttpHeaders.Names.COOKIE, httpCookieEncoder.encode());

        //set some example content
        final ChannelBuffer content = ChannelBuffers.wrappedBuffer("this is a LARGE test from http client".getBytes());
        request.setContent(content);
        request.setHeader(HttpHeaders.Names.CONTENT_LENGTH, String.valueOf(content.readableBytes()));

        //debug request
        debugRequest(request);
        // Send the HTTP request.
        channel.write(request);

        // Wait for the server to close the connection.
        channel.getCloseFuture().awaitUninterruptibly();

        // Shut down executor threads to exit.
        bootstrap.releaseExternalResources();
    }

	private static void debugRequest(final HttpRequest request){
		System.out.println("HTTP Request: "+ request.getUri());
		System.out.println("-------------------------------------------");

		if(!request.getHeaderNames().isEmpty()){
			for(String name: request.getHeaderNames()){
				for(String value: request.getHeaders(name)){
					System.out.println("HEADER: "+name+":" + value);
				}
			}
		}

		ChannelBuffer content = request.getContent();
		if(content.readable()){
			System.out.println("PAYLOAD: "+content.toString(CharsetUtil.UTF_8));
		}

		System.out.println("-------------------------------------------");
	}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            System.err.println(
                    "Usage: " + HttpSnoopClient.class.getSimpleName() +
                    " <URL>");
            return;
        }

        URI uri = new URI(args[0]);
        new HttpSnoopClient(uri).run();
    }
}
