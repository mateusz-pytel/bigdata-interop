/**
 * Copyright 2013 Google Inc. All Rights Reserved.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *    http://www.apache.org/licenses/LICENSE-2.0
 *    
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.cloud.hadoop.util;

import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenRequest;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.extensions.java6.auth.oauth2.AuthorizationCodeInstalledApp;
import com.google.api.client.extensions.java6.auth.oauth2.FileCredentialStore;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.compute.ComputeCredential;
import com.google.api.client.googleapis.extensions.java6.auth.oauth2.GooglePromptReceiver;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.GenericUrl;
import com.google.api.client.http.HttpBackOffIOExceptionHandler;
import com.google.api.client.http.HttpBackOffUnsuccessfulResponseHandler;
import com.google.api.client.http.HttpRequest;
import com.google.api.client.http.HttpRequestInitializer;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.JsonObjectParser;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.client.json.webtoken.JsonWebSignature;
import com.google.api.client.json.webtoken.JsonWebToken;
import com.google.api.client.util.ExponentialBackOff;
import com.google.api.client.util.Joiner;
import com.google.api.services.storage.StorageScopes;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;

import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.List;

/**
 * Miscellaneous helper methods for getting a {@code Credential} from various sources.
 */
public class CredentialFactory {

  /**
   * Simple HttpRequestInitializer that retries requests that result in 5XX response codes and
   * IO Exceptions with an exponential backoff.
   */
  public static class CredentialHttpRetryInitializer implements HttpRequestInitializer {
    @Override
    public void initialize(HttpRequest httpRequest) throws IOException {
      httpRequest.setIOExceptionHandler(
          new HttpBackOffIOExceptionHandler(new ExponentialBackOff()));
      httpRequest.setUnsuccessfulResponseHandler(
          new HttpBackOffUnsuccessfulResponseHandler(new ExponentialBackOff()));
    }
  }

  /**
   * A subclass of {@link GoogleCredential} that properly wires specified
   * {@link HttpRequestInitializer} through the @{link Credential#executeRefreshToken} override.
   * <p>
   * We will not retry 403 "invalid_request" rate limiting errors. See the following for
   * more on rate limiting in OAuth:
   * https://code.google.com/p/google-api-java-client/issues/detail?id=879
   */
  public static class GoogleCredentialWithRetry extends GoogleCredential {

    private static final int DEFAULT_TOKEN_EXPIRATION_SECONDS = 3600;

    public GoogleCredentialWithRetry(Builder builder) {
      super(builder);
    }

    @Override
    protected TokenResponse executeRefreshToken() throws IOException {
      if (getServiceAccountPrivateKey() == null) {
        return super.executeRefreshToken();
      }
      // service accounts: no refresh token; instead use private key to request new access token
      JsonWebSignature.Header header = new JsonWebSignature.Header();
      header.setAlgorithm("RS256");
      header.setType("JWT");
      header.setKeyId(getServiceAccountPrivateKeyId());
      JsonWebToken.Payload payload = new JsonWebToken.Payload();
      long currentTime = getClock().currentTimeMillis();
      payload.setIssuer(getServiceAccountId());
      payload.setAudience(getTokenServerEncodedUrl());
      payload.setIssuedAtTimeSeconds(currentTime / 1000);
      payload.setExpirationTimeSeconds(currentTime / 1000 + DEFAULT_TOKEN_EXPIRATION_SECONDS);
      payload.setSubject(getServiceAccountUser());
      payload.put("scope", Joiner.on(' ').join(getServiceAccountScopes()));
      try {
        String assertion = JsonWebSignature.signUsingRsaSha256(
            getServiceAccountPrivateKey(), getJsonFactory(), header, payload);
        TokenRequest request = new TokenRequest(
            getTransport(), getJsonFactory(), new GenericUrl(getTokenServerEncodedUrl()),
            "urn:ietf:params:oauth:grant-type:jwt-bearer");
        request.put("assertion", assertion);
        request.setRequestInitializer(getRequestInitializer());
        return request.execute();
      } catch (GeneralSecurityException exception) {
        IOException e = new IOException();
        e.initCause(exception);
        throw e;
      }
    }
  }

  /**
   * A subclass of ComputeCredential that properly sets request initializers.
   */
  public static class ComputeCredentialWithRetry extends ComputeCredential {
    public ComputeCredentialWithRetry(Builder builder) {
      super(builder);
    }

    @Override
    protected TokenResponse executeRefreshToken() throws IOException {
      GenericUrl tokenUrl = new GenericUrl(getTokenServerEncodedUrl());
      HttpRequest request =
          getTransport()
              .createRequestFactory(getRequestInitializer())
              .buildGetRequest(tokenUrl);
      request.setParser(new JsonObjectParser(getJsonFactory()));
      request.getHeaders().set("X-Google-Metadata-Request", true);
      return request.execute().parseAs(TokenResponse.class);
    }
  }

  // List of GCS scopes to specify when obtaining a credential.
  public static final List<String> GCS_SCOPES =
      ImmutableList.of(StorageScopes.DEVSTORAGE_FULL_CONTROL);

  public static final List<String> DATASTORE_SCOPES =
      ImmutableList.of("https://www.googleapis.com/auth/datastore",
                       "https://www.googleapis.com/auth/userinfo.email");

  // Logger.
  private static final LogUtil log = new LogUtil(CredentialFactory.class);

  // JSON factory used for formatting credential-handling payloads.
  private static final JsonFactory JSON_FACTORY = new JacksonFactory();

  // HTTP transport used for created credentials to perform token-refresh handshakes with remote
  // credential servers. Initialized lazily to move the possibility of throwing
  // GeneralSecurityException to the time a caller actually tries to get a credential.
  private static HttpTransport httpTransport = null;

  /**
   * Returns shared httpTransport instance; initializes httpTransport if it hasn't already been
   * initialized.
   */
  private static synchronized HttpTransport getHttpTransport()
      throws IOException, GeneralSecurityException {
    if (httpTransport == null) {
      httpTransport = GoogleNetHttpTransport.newTrustedTransport();
    }
    return httpTransport;
  }

  /**
   * Initializes OAuth2 credential using preconfigured ServiceAccount settings on the local
   * GCE VM. See: <a href="https://developers.google.com/compute/docs/authentication"
   * >Authenticating from Google Compute Engine</a>.
   */
  public Credential getCredentialFromMetadataServiceAccount()
      throws IOException, GeneralSecurityException {
    log.debug("getCredentialFromMetadataServiceAccount()");
    Credential cred = new ComputeCredentialWithRetry(
        new ComputeCredential.Builder(getHttpTransport(), JSON_FACTORY)
            .setRequestInitializer(new CredentialHttpRetryInitializer()));
    try {
      cred.refreshToken();
    } catch (IOException e) {
      throw new IOException("Error getting access token from metadata server at: " +
          ComputeCredential.TOKEN_SERVER_ENCODED_URL, e);
    }
    return cred;
  }

  /**
   * Initializes OAuth2 credential from a private keyfile, as described in
   * <a href="https://code.google.com/p/google-api-java-client/wiki/OAuth2#Service_Accounts"
   * > OAuth2 Service Accounts</a>.
   *
   * @param serviceAccountEmail Email address of the service account associated with the keyfile.
   * @param privateKeyFile Full local path to private keyfile.
   * @param scopes List of well-formed desired scopes to use with the credential.
   */
  public Credential getCredentialFromPrivateKeyServiceAccount(
      String serviceAccountEmail, String privateKeyFile, List<String> scopes)
      throws IOException, GeneralSecurityException {
    log.debug("getCredentialFromPrivateKeyServiceAccount(%s, %s, %s)",
        serviceAccountEmail, privateKeyFile, scopes);

    return new GoogleCredentialWithRetry(
        new GoogleCredential.Builder()
          .setTransport(getHttpTransport())
          .setJsonFactory(JSON_FACTORY)
          .setServiceAccountId(serviceAccountEmail)
          .setServiceAccountScopes(scopes)
          .setServiceAccountPrivateKeyFromP12File(new File(privateKeyFile))
          .setRequestInitializer(new CredentialHttpRetryInitializer()));
  }

  /**
   * Initialized OAuth2 credential for the "installed application" flow; where the credential
   * typically represents an actual end user (instead of a service account), and is stored
   * as a refresh token in a local FileCredentialStore.
   * @param clientId OAuth2 client ID identifying the 'installed app'
   * @param clientSecret OAuth2 client secret
   * @param filePath full path to a ".json" file for storing the credential
   * @param scopes list of well-formed scopes desired in the credential
   * @return credential with desired scopes, possibly obtained from loading {@code filePath}.
   * @throws IOException on IO error
   */
  public Credential getCredentialFromFileCredentialStoreForInstalledApp(
      String clientId, String clientSecret, String filePath, List<String> scopes)
      throws IOException, GeneralSecurityException {
    log.debug("getCredentialFromFileCredentialStoreForInstalledApp(%s, %s, %s, %s)",
        clientId, clientSecret, filePath, scopes);
    Preconditions.checkArgument(!Strings.isNullOrEmpty(clientId),
        "clientId must not be null or empty");
    Preconditions.checkArgument(!Strings.isNullOrEmpty(clientSecret),
        "clientSecret must not be null or empty");
    Preconditions.checkArgument(!Strings.isNullOrEmpty(filePath),
        "filePath must not be null or empty");
    Preconditions.checkArgument(scopes != null,
        "scopes must not be null or empty");

    // Initialize client secrets.
    GoogleClientSecrets.Details details = new GoogleClientSecrets.Details();
    details.setClientId(clientId);
    details.setClientSecret(clientSecret);
    GoogleClientSecrets clientSecrets = new GoogleClientSecrets();
    clientSecrets.setInstalled(details);

    // Set up file credential store.
    FileCredentialStore credentialStore =
        new FileCredentialStore(new File(filePath), JSON_FACTORY);

    // Set up authorization code flow.
    GoogleAuthorizationCodeFlow flow =
        new GoogleAuthorizationCodeFlow.Builder(
            getHttpTransport(),
            JSON_FACTORY,
            clientSecrets,
            scopes)
        .setCredentialStore(credentialStore)
        .setRequestInitializer(new CredentialHttpRetryInitializer())
        .build();

    // Authorize access.
    return new AuthorizationCodeInstalledApp(flow, new GooglePromptReceiver()).authorize("user");
  }

  /**
   * Initializes OAuth2 credential and obtains authorization to access GCS.
   *
   * @param clientId OAuth2 client ID
   * @param clientSecret OAuth2 client secret
   * @return credential that allows access to GCS
   * @throws IOException on IO error
   */
  public Credential getStorageCredential(String clientId, String clientSecret)
      throws IOException, GeneralSecurityException {
    log.debug("getStorageCredential(%s, %s)", clientId, clientSecret);
    String filePath = System.getProperty("user.home") + "/.credentials/storage.json";
    return getCredentialFromFileCredentialStoreForInstalledApp(
        clientId, clientSecret, filePath, GCS_SCOPES);
  }
  /**
   * Initializes OAuth2 credential and obtains authorization to access Datastore.
   *
   * @param clientId OAuth2 client ID
   * @param clientSecret OAuth2 client secret
   * @return credential that allows access to Datastore
   * @throws IOException on IO error
   */
  public Credential getDatastoreCredential(String clientId, String clientSecret)
      throws IOException, GeneralSecurityException {
    log.debug("getStorageCredential(%s, %s)", clientId, clientSecret);
    String filePath = System.getProperty("user.home") + "/.credentials/datastore.json";
    return getCredentialFromFileCredentialStoreForInstalledApp(
        clientId, clientSecret, filePath, DATASTORE_SCOPES);
  }
}
