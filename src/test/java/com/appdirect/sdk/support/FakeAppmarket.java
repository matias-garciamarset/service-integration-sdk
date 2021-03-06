/*
 * Copyright 2017 AppDirect, Inc. and/or its affiliates
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.appdirect.sdk.support;

import static com.appdirect.sdk.support.ContentOf.resourceAsString;
import static com.appdirect.sdk.support.ContentOf.streamAsString;
import static com.appdirect.sdk.support.HttpClientHelper.anAppmarketHttpClient;
import static com.appdirect.sdk.support.HttpClientHelper.buildURI;
import static com.appdirect.sdk.support.HttpClientHelper.get;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import lombok.RequiredArgsConstructor;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.message.BasicHeader;

import com.appdirect.sdk.appmarket.domain.DomainVerificationStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import oauth.signpost.OAuthConsumer;
import oauth.signpost.commonshttp.CommonsHttpOAuthConsumer;
import oauth.signpost.exception.OAuthCommunicationException;
import oauth.signpost.exception.OAuthExpectationFailedException;
import oauth.signpost.exception.OAuthMessageSignerException;

public class FakeAppmarket {
	private final HttpServer server;
	private final String isvKey;
	private final String isvSecret;
	private final List<String> allRequestPaths;
	private final List<String> resolvedEvents;
	private final Object resolvedEventsLock = new Object();
	private final List<DomainVerificationStatus> domainVerificationStatuses;
	private final Object domainVerificationStatusesLock = new Object();
	private String lastRequestBody;
	private Throwable backgroundThreadException;

	public static FakeAppmarket create(int port, String isvKey, String isvSecret) throws IOException {
		HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
		return new FakeAppmarket(server, isvKey, isvSecret);
	}

	private FakeAppmarket(HttpServer server, String isvKey, String isvSecret) {
		this.server = server;
		this.isvKey = isvKey;
		this.isvSecret = isvSecret;
		this.allRequestPaths = new ArrayList<>();
		this.resolvedEvents = new ArrayList<>();
		this.domainVerificationStatuses = new ArrayList<>();
	}

	public FakeAppmarket start() {
		Predicate<HttpExchange> oauthInTheHeader = httpExchange -> {
			String authorization = httpExchange.getRequestHeaders().getFirst("Authorization");
			return authorization != null && authorization.startsWith("OAuth oauth_consumer_key=\"" + isvKey + "\",");
		};

		server.createContext("/v1/events/", new ReturnResourceContent(oauthInTheHeader));
		server.createContext("/api/integration/v1/events/", new OauthSecuredHandler(oauthInTheHeader) {
			@Override
			byte[] buildJsonResponse(URI requestUri) throws IOException {
				String eventToken = requestUri.getPath().split("/")[5];
				markEventAsResolved(eventToken);
				return "".getBytes(UTF_8);
			}
		});
		server.createContext("/api/integration/v1/customers/", new OauthSecuredHandler(oauthInTheHeader) {
			@Override
			byte[] buildJsonResponse(URI requestUri) throws IOException {
				String body = new String(lastRequestBody);
				ObjectMapper objectMapper = new ObjectMapper();
				DomainVerificationStatus domainVerificationStatus = objectMapper.readValue(body, DomainVerificationStatus.class);
				synchronized (resolvedEventsLock) {
					domainVerificationStatuses.add(domainVerificationStatus);
					resolvedEventsLock.notify();
				}
				return "".getBytes(UTF_8);
			}
		});

		server.start();
		return this;
	}

	public void stop() {
		server.stop(0);
		if (backgroundThreadException != null) {
			IllegalThreadStateException illegalThreadStateException = new IllegalThreadStateException("One of the FakeAppMarket's request thread threw an exception. This is bad.");
			illegalThreadStateException.initCause(backgroundThreadException);
			throw illegalThreadStateException;
		}
	}

	public String lastRequestPath() {
		return lastItemOrNull(allRequestPaths);
	}

	public List<String> allRequestPaths() {
		return new ArrayList<>(allRequestPaths);
	}

	public String lastRequestBody() {
		return lastRequestBody;
	}

	public List<String> resolvedEvents() {
		return new ArrayList<>(resolvedEvents);
	}

	public List<DomainVerificationStatus> domainVerificationStatuses() {
		return new ArrayList<>(domainVerificationStatuses);
	}

	private void markEventAsResolved(String eventToken) {
		synchronized (resolvedEventsLock) {
			resolvedEvents.add(eventToken);
			resolvedEventsLock.notify();
		}
	}

	public HttpResponse sendEventTo(String connectorEventEndpointUrl, String appmarketEventPath, String... extraQueryParameters) throws Exception {
		List<String> allParams = new ArrayList<>();
		allParams.add("eventUrl");
		allParams.add(baseAppmarketUrl() + appmarketEventPath);
		allParams.addAll(asList(extraQueryParameters));

		return sendSignedRequestTo(connectorEventEndpointUrl, allParams);
	}

	public HttpResponse callDummyRestController(String dummyControllerEndpointUrl, BasicHeader... header) throws Exception {
		return sendSignedRequestTo(dummyControllerEndpointUrl, emptyList(), header);
	}

	public HttpResponse sendSignedRequestTo(String connectorEventEndpointUrl, List<String> allParams, Header... headers) throws Exception {
		CloseableHttpClient httpClient = anAppmarketHttpClient();
		HttpGet request = get(connectorEventEndpointUrl, allParams.toArray(new String[]{}));

		oauthSign(request);
		addHeaders(request, headers);

		return httpClient.execute(request);
	}

	public HttpResponse sendSignedDeleteRequestTo(String url) throws Exception {
		CloseableHttpClient httpClient = anAppmarketHttpClient();
		HttpDelete request = new HttpDelete(new URIBuilder(url).build());

		oauthSign(request);
		return httpClient.execute(request);
	}

	public HttpResponse sendSignedPostRequestTo(String url, HttpEntity httpEntity) throws Exception {
		CloseableHttpClient httpClient = anAppmarketHttpClient();
		HttpPost request = new HttpPost(new URIBuilder(url).build());
		request.setEntity(httpEntity);

		oauthSign(request);
		return httpClient.execute(request);
	}

	public HttpResponse sendTriggerDomainVerificationTo(String connectorDomainVerificationUrl, String appmarketCallbackPath) throws Exception {
		List<String> allParams = new ArrayList<>();
		allParams.add("callbackUrl");
		allParams.add(baseAppmarketUrl() + appmarketCallbackPath);

		return sendSignedPostRequestTo(buildURI(connectorDomainVerificationUrl, allParams.toArray(new String[]{})).toURL().toString(), new StringEntity(""));
	}

	private String baseAppmarketUrl() {
		return "http://localhost:" + server.getAddress().getPort();
	}

	private <T> T lastItemOrNull(List<T> list) {
		return list.isEmpty() ? null : list.get(list.size() - 1);
	}

	private void oauthSign(HttpRequest request) throws OAuthMessageSignerException, OAuthExpectationFailedException, OAuthCommunicationException {
		OAuthConsumer consumer = new CommonsHttpOAuthConsumer(isvKey, isvSecret);
		consumer.sign(request);
	}

	private void addHeaders(HttpGet request, Header[] headers) {
		for (Header header : headers) {
			request.addHeader(header);
		}
	}

	public void waitForResolvedEvents(int desiredNumberOfResolvedEvents) throws Exception {
		long maxNumberOfTries = 100, timeoutOfOneTryMs = 50;
		synchronized (resolvedEventsLock) {
			int tries = 0;
			while (resolvedEvents.size() < desiredNumberOfResolvedEvents && tries < maxNumberOfTries) {
				tries++;
				resolvedEventsLock.wait(timeoutOfOneTryMs);
			}

			if (tries == maxNumberOfTries) {
				throw new TimeoutException("Could not find " + desiredNumberOfResolvedEvents + " resolved event after trying for " + maxNumberOfTries * timeoutOfOneTryMs + "ms. | resolvedEvents: " + resolvedEvents);
			}
		}
	}

	public void waitForDomainVerifications(int desiredNumberOfDomainVerificationStatuses) throws Exception {
		long maxNumberOfTries = 100, timeoutOfOneTryMs = 50;
		synchronized (domainVerificationStatusesLock) {
			int tries = 0;
			while (domainVerificationStatuses.size() < desiredNumberOfDomainVerificationStatuses && tries < maxNumberOfTries) {
				tries++;
				domainVerificationStatusesLock.wait(timeoutOfOneTryMs);
			}

			if (tries == maxNumberOfTries) {
				throw new TimeoutException("Could not find " + desiredNumberOfDomainVerificationStatuses + " domain verifications after trying for " + maxNumberOfTries * timeoutOfOneTryMs + "ms. | domainVerificationStatuses: " + domainVerificationStatuses);
			}
		}
	}

	class ReturnResourceContent extends OauthSecuredHandler {
		ReturnResourceContent(Predicate<HttpExchange> authorized) {
			super(authorized);
		}

		@Override
		byte[] buildJsonResponse(URI requestUri) throws IOException {
			String jsonResource = buildJsonResourceFrom(requestUri);
			return resourceAsString(jsonResource)
					.replace("{{fake-appmarket-url}}", baseAppmarketUrl())
					.replace("{{account-id}}", getOptionalAccountIdParam(requestUri))
					.getBytes(UTF_8);
		}

		private String buildJsonResourceFrom(URI requestUri) {
			String[] fragments = requestUri.getPath().split("/");
			String resourceName = fragments[fragments.length - 1];
			return "events/" + resourceName + ".json";
		}

		private String getOptionalAccountIdParam(URI requestURI) {
			String query = requestURI.getQuery();
			if (query == null || !query.startsWith("account-id")) {
				return "";
			}
			return query.split("=")[1];
		}
	}

	@RequiredArgsConstructor
	abstract class OauthSecuredHandler implements HttpHandler {
		private final Predicate<HttpExchange> authorized;

		@Override
		public void handle(HttpExchange t) throws IOException {
			byte[] response = "".getBytes(UTF_8);
			try {
				allRequestPaths.add(t.getRequestURI().toString());

				if (!authorized.test(t)) {
					sendResponse(t, 401, "UNAUTHORIZED! Use OAUTH!".getBytes(UTF_8));
					return;
				}
				lastRequestBody = streamAsString(t.getRequestBody());

				t.getResponseHeaders().add("Content-Type", "application/json");
				response = buildJsonResponse(t.getRequestURI());
			} catch (Exception e) {
				backgroundThreadException = e;
			} finally {
				sendResponse(t, 200, response);
			}
		}

		abstract byte[] buildJsonResponse(URI requestUri) throws IOException;

		private void sendResponse(HttpExchange t, int statusCode, byte[] response) throws IOException {
			t.sendResponseHeaders(statusCode, response.length);

			OutputStream os = t.getResponseBody();
			os.write(response);
			os.close();
		}
	}
}
