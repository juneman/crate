/*
 * This file is part of a module with proprietary Enterprise Features.
 *
 * Licensed to Crate.io Inc. ("Crate.io") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 *
 * Unauthorized copying of this file, via any medium is strictly prohibited.
 *
 * To use this file, Crate.io must have given you permission to enable and
 * use such Enterprise Features and you must have a valid Enterprise or
 * Subscription Agreement with Crate.io.  If you enable or use the Enterprise
 * Features, you represent and warrant that you have a valid Enterprise or
 * Subscription Agreement with Crate.io.  Your use of the Enterprise Features
 * if governed by the terms and conditions of your Enterprise or Subscription
 * Agreement with Crate.io.
 */

package io.crate.http.netty;

import io.crate.action.sql.SessionContext;
import io.crate.concurrent.CompletableFutures;
import io.crate.operation.auth.Authentication;
import io.crate.operation.auth.AuthenticationMethod;
import io.crate.operation.auth.AuthenticationProvider;
import io.crate.operation.auth.HbaProtocol;
import io.crate.test.integration.CrateUnitTest;
import org.elasticsearch.common.network.InetAddresses;
import org.jboss.netty.buffer.ChannelBuffer;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.*;
import org.jboss.netty.handler.codec.http.*;
import org.jboss.netty.util.CharsetUtil;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Matchers;

import javax.annotation.Nullable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;

import static org.elasticsearch.mock.orig.Mockito.times;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.*;

public class HttpAuthUpstreamHandlerTest extends CrateUnitTest {

    private InetSocketAddress IPv4_LOCALHOST = new InetSocketAddress(InetAddresses.forString("127.0.0.1"), 54321);

    private final AuthenticationMethod denyAll = new AuthenticationMethod() {
        @Override
        public CompletableFuture<Boolean> pgAuthenticate(Channel channel, SessionContext session) {
            return CompletableFutures.COMPLETED_FALSE;
        }

        @Override
        public String name() {
            return "denyAll";
        }

        @Override
        public CompletableFuture<Boolean> httpAuthentication(String username) {
            return CompletableFutures.COMPLETED_FALSE;
        }
    };

    private final Authentication authService = new Authentication() {
        @Override
        public boolean enabled() {
            return true;
        }

        @Nullable
        @Override
        public AuthenticationMethod resolveAuthenticationType(String user, InetAddress address, HbaProtocol protocol) {
            // we want to test two things:
            // 1) no authentication method
            // 2) deny authentication
            if ("null".equals(user)) {
                return null;
            }
            return denyAll;
        }
    };

    private ChannelHandlerContext getChannelHandlerContext(Channel ch) {
        ChannelFuture cf = new DefaultChannelFuture(ch, false);
        when(ch.write(Matchers.any())).thenReturn(cf);
        when(ch.getRemoteAddress()).thenReturn(IPv4_LOCALHOST);

        ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
        when(ctx.getChannel()).thenReturn(ch);
        when(ctx.canHandleUpstream()).thenReturn(true);
        return ctx;
    }

    private void assertUnauthorized(Channel ch, @Nullable String error) {
        ArgumentCaptor<HttpResponse> writeCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(ch).write(writeCaptor.capture());
        HttpResponse response = writeCaptor.getValue();
        assertThat(response.getStatus(), is(HttpResponseStatus.UNAUTHORIZED));
        ChannelBuffer content = error == null ? ChannelBuffers.EMPTY_BUFFER : ChannelBuffers.copiedBuffer(error, CharsetUtil.US_ASCII);
        assertThat(response.getContent(), is(content));
    }

    @Test
    public void testChannelClosedWhenUnauthorized() throws Exception {
        Channel ch = mock(Channel.class);
        ChannelFuture cf = new DefaultChannelFuture(ch, false);
        when(ch.write(Matchers.any())).thenReturn(cf);

        HttpAuthUpstreamHandler.sendUnauthorized(ch, null);
        cf.setSuccess();
        verify(ch, times(1)).close();
    }

    @Test
    public void testSendUnauthorizedWithoutBody() throws Exception {
        Channel ch = mock(Channel.class);
        ChannelFuture cf = new DefaultChannelFuture(ch, false);
        when(ch.write(Matchers.any())).thenReturn(cf);

        HttpAuthUpstreamHandler.sendUnauthorized(ch, null);

        ArgumentCaptor<HttpResponse> writeCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(ch).write(writeCaptor.capture());
        HttpResponse response = writeCaptor.getValue();
        assertThat(response.getStatus(), is(HttpResponseStatus.UNAUTHORIZED));
        assertThat(response.getContent(), is(ChannelBuffers.EMPTY_BUFFER));
    }

    @Test
    public void testSendUnauthorizedWithBody() throws Exception {
        Channel ch = mock(Channel.class);
        ChannelFuture cf = new DefaultChannelFuture(ch, false);
        when(ch.write(Matchers.any())).thenReturn(cf);

        String error = "not allowed\n";
        HttpAuthUpstreamHandler.sendUnauthorized(ch, error);
        assertUnauthorized(ch, error);
    }

    @Test
    public void testSendUnauthorizedWithBodyNoNewline() throws Exception {
        Channel ch = mock(Channel.class);
        ChannelFuture cf = new DefaultChannelFuture(ch, false);
        when(ch.write(Matchers.any())).thenReturn(cf);

        HttpAuthUpstreamHandler.sendUnauthorized(ch, "not allowed");
        assertUnauthorized(ch, "not allowed\n");
    }

    @Test
    public void testAuthorized() throws Exception {
        HttpAuthUpstreamHandler handler = new HttpAuthUpstreamHandler(AuthenticationProvider.NOOP_AUTH);

        Channel ch = mock(Channel.class);
        ChannelHandlerContext ctx = getChannelHandlerContext(ch);

        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/_sql");

        MessageEvent e = mock(MessageEvent.class);
        when(e.getMessage()).thenReturn(request);

        handler.messageReceived(ctx, e);
        assertTrue(handler.authorized());
        verify(ctx, times(1)).sendUpstream(e);

    }

    @Test
    public void testNotNoHbaConfig() throws Exception {
        HttpAuthUpstreamHandler handler = new HttpAuthUpstreamHandler(authService);

        Channel ch = mock(Channel.class);
        ChannelHandlerContext ctx = getChannelHandlerContext(ch);

        DefaultHttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/_sql") {
            @Override
            public HttpHeaders headers() {
                DefaultHttpHeaders headers = new DefaultHttpHeaders();
                headers.add("X-User", "null");
                return headers;
            }
        };

        MessageEvent e = mock(MessageEvent.class);
        when(e.getMessage()).thenReturn(request);

        handler.messageReceived(ctx, e);
        assertFalse(handler.authorized());
        assertUnauthorized(ch, "No valid auth.host_based.config entry found for host \"127.0.0.1\", user \"null\", protocol \"http\"\n");

    }

    @Test
    public void testUnauthorizedUser() throws Exception {
        HttpAuthUpstreamHandler handler = new HttpAuthUpstreamHandler(authService);

        Channel ch = mock(Channel.class);
        ChannelHandlerContext ctx = getChannelHandlerContext(ch);

        HttpRequest request = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/_sql");

        MessageEvent e = mock(MessageEvent.class);
        when(e.getMessage()).thenReturn(request);

        handler.messageReceived(ctx, e);
        assertFalse(handler.authorized());
        assertUnauthorized(ch, "Authentication failed for user \"crate\" and method \"denyAll\".\n");
    }
}
