/*
 * Copyright 2020-2022 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.security.oauth2.server.authorization.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.log.LogMessage;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.http.converter.OAuth2ErrorHttpMessageConverter;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenIntrospection;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenIntrospectionAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2TokenIntrospectionAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.http.converter.OAuth2TokenIntrospectionHttpMessageConverter;
import org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2TokenIntrospectionAuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.util.Assert;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * A {@code Filter} for the OAuth 2.0 Token Introspection endpoint.
 *
 * @author Gerardo Roza
 * @author Joe Grandja
 * @author Gaurav Tiwari
 * @see OAuth2TokenIntrospectionAuthenticationProvider
 * @see <a target="_blank" href="https://tools.ietf.org/html/rfc7662#section-2">Section 2 Introspection Endpoint</a>
 * @see <a target="_blank" href="https://tools.ietf.org/html/rfc7662#section-2.1">Section 2.1 Introspection Request</a>
 * @since 0.1.1
 */
public final class OAuth2TokenIntrospectionEndpointFilter extends OncePerRequestFilter {
	/**
	 * The default endpoint {@code URI} for token introspection requests.
	 */
	private static final String DEFAULT_TOKEN_INTROSPECTION_ENDPOINT_URI = "/oauth2/introspect";

	private final AuthenticationManager authenticationManager;
	private final RequestMatcher tokenIntrospectionEndpointMatcher;
	private AuthenticationConverter authenticationConverter;
	private final HttpMessageConverter<OAuth2TokenIntrospection> tokenIntrospectionHttpResponseConverter =
			new OAuth2TokenIntrospectionHttpMessageConverter();
	private final HttpMessageConverter<OAuth2Error> errorHttpResponseConverter = new OAuth2ErrorHttpMessageConverter();
	private AuthenticationSuccessHandler authenticationSuccessHandler = this::sendIntrospectionResponse;
	private AuthenticationFailureHandler authenticationFailureHandler = this::sendErrorResponse;

	/**
	 * Constructs an {@code OAuth2TokenIntrospectionEndpointFilter} using the provided parameters.
	 *
	 * @param authenticationManager the authentication manager
	 */
	public OAuth2TokenIntrospectionEndpointFilter(AuthenticationManager authenticationManager) {
		this(authenticationManager, DEFAULT_TOKEN_INTROSPECTION_ENDPOINT_URI);
	}

	/**
	 * Constructs an {@code OAuth2TokenIntrospectionEndpointFilter} using the provided parameters.
	 *
	 * @param authenticationManager the authentication manager
	 * @param tokenIntrospectionEndpointUri the endpoint {@code URI} for token introspection requests
	 */
	public OAuth2TokenIntrospectionEndpointFilter(AuthenticationManager authenticationManager,
			String tokenIntrospectionEndpointUri) {
		this(authenticationManager, createDefaultRequestMatcher(tokenIntrospectionEndpointUri));
	}

	/**
	 * Constructs an {@code OAuth2TokenIntrospectionEndpointFilter} using the provided parameters.
	 *
	 * @param authenticationManager the authentication manager
	 * @param tokenIntrospectionEndpointMatcher the request matcher for token introspection requests
	 */
	public OAuth2TokenIntrospectionEndpointFilter(AuthenticationManager authenticationManager,
			RequestMatcher tokenIntrospectionEndpointMatcher) {
		Assert.notNull(authenticationManager, "authenticationManager cannot be null");
		Assert.notNull(tokenIntrospectionEndpointMatcher, "tokenIntrospectionEndpointMatcher cannot be null");
		this.authenticationManager = authenticationManager;
		this.tokenIntrospectionEndpointMatcher = tokenIntrospectionEndpointMatcher;
		this.authenticationConverter = new OAuth2TokenIntrospectionAuthenticationConverter();
	}

	public static RequestMatcher createDefaultRequestMatcher(String tokenIntrospectionEndpointUri) {
		Assert.hasText(tokenIntrospectionEndpointUri, "tokenIntrospectionEndpointUri cannot be empty");
		return new AntPathRequestMatcher(tokenIntrospectionEndpointUri, HttpMethod.POST.name());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		if (!this.tokenIntrospectionEndpointMatcher.matches(request)) {
			filterChain.doFilter(request, response);
			return;
		}

		try {
			Authentication tokenIntrospectionAuthentication = this.authenticationConverter.convert(request);
			Authentication tokenIntrospectionAuthenticationResult =
					this.authenticationManager.authenticate(tokenIntrospectionAuthentication);
			this.authenticationSuccessHandler.onAuthenticationSuccess(request, response, tokenIntrospectionAuthenticationResult);
		} catch (OAuth2AuthenticationException ex) {
			SecurityContextHolder.clearContext();
			if (this.logger.isTraceEnabled()) {
				this.logger.trace(LogMessage.format("Token introspection request failed: %s", ex.getError()), ex);
			}
			this.authenticationFailureHandler.onAuthenticationFailure(request, response, ex);
		}
	}

	/**
	 * Sets the {@link AuthenticationConverter} used when attempting to extract an Introspection Request from {@link HttpServletRequest}
	 * to an instance of {@link OAuth2TokenIntrospectionAuthenticationToken} used for authenticating the request.
	 *
	 * @param authenticationConverter the {@link AuthenticationConverter} used when attempting to extract an Introspection Request from {@link HttpServletRequest}
	 * @since 0.2.3
	 */
	public void setAuthenticationConverter(AuthenticationConverter authenticationConverter) {
		Assert.notNull(authenticationConverter, "authenticationConverter cannot be null");
		this.authenticationConverter = authenticationConverter;
	}

	/**
	 * Sets the {@link AuthenticationSuccessHandler} used for handling an {@link OAuth2TokenIntrospectionAuthenticationToken}.
	 *
	 * @param authenticationSuccessHandler the {@link AuthenticationSuccessHandler} used for handling an {@link OAuth2TokenIntrospectionAuthenticationToken}
	 * @since 0.2.3
	 */
	public void setAuthenticationSuccessHandler(AuthenticationSuccessHandler authenticationSuccessHandler) {
		Assert.notNull(authenticationSuccessHandler, "authenticationSuccessHandler cannot be null");
		this.authenticationSuccessHandler = authenticationSuccessHandler;
	}

	/**
	 * Sets the {@link AuthenticationFailureHandler} used for handling an {@link OAuth2AuthenticationException}
	 * and returning the {@link OAuth2Error Error Resonse}.
	 *
	 * @param authenticationFailureHandler the {@link AuthenticationFailureHandler} used for handling an {@link OAuth2AuthenticationException}
	 * @since 0.2.3
	 */
	public void setAuthenticationFailureHandler(AuthenticationFailureHandler authenticationFailureHandler) {
		Assert.notNull(authenticationFailureHandler, "authenticationFailureHandler cannot be null");
		this.authenticationFailureHandler = authenticationFailureHandler;
	}

	private void sendIntrospectionResponse(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {

		OAuth2TokenIntrospectionAuthenticationToken tokenIntrospectionAuthentication =
				(OAuth2TokenIntrospectionAuthenticationToken) authentication;
		OAuth2TokenIntrospection tokenClaims = tokenIntrospectionAuthentication.getTokenClaims();
		ServletServerHttpResponse httpResponse = new ServletServerHttpResponse(response);
		this.tokenIntrospectionHttpResponseConverter.write(tokenClaims, null, httpResponse);
	}

	private void sendErrorResponse(HttpServletRequest request, HttpServletResponse response,
			AuthenticationException exception) throws IOException {
		OAuth2Error error = ((OAuth2AuthenticationException) exception).getError();
		ServletServerHttpResponse httpResponse = new ServletServerHttpResponse(response);
		httpResponse.setStatusCode(HttpStatus.BAD_REQUEST);
		this.errorHttpResponseConverter.write(error, null, httpResponse);
	}

}
