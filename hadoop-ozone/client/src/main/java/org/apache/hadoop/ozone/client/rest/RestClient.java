/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.ozone.client.rest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.StorageType;
import org.apache.hadoop.hdds.protocol.proto.HddsProtos;
import org.apache.hadoop.hdds.scm.client.HddsClientUtils;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.ozone.OzoneAcl;
import org.apache.hadoop.ozone.OzoneConfigKeys;
import org.apache.hadoop.ozone.OzoneConsts;
import org.apache.hadoop.ozone.client.BucketArgs;
import org.apache.hadoop.ozone.client.OzoneBucket;
import org.apache.hadoop.ozone.client.OzoneKey;
import org.apache.hadoop.hdds.client.OzoneQuota;
import org.apache.hadoop.ozone.client.OzoneVolume;
import org.apache.hadoop.hdds.client.ReplicationFactor;
import org.apache.hadoop.hdds.client.ReplicationType;
import org.apache.hadoop.ozone.client.VolumeArgs;
import org.apache.hadoop.ozone.client.io.OzoneInputStream;
import org.apache.hadoop.ozone.client.io.OzoneOutputStream;
import org.apache.hadoop.ozone.client.protocol.ClientProtocol;
import org.apache.hadoop.ozone.client.rest.headers.Header;
import org.apache.hadoop.ozone.client.rest.response.BucketInfo;
import org.apache.hadoop.ozone.client.rest.response.KeyInfo;
import org.apache.hadoop.ozone.client.rest.response.VolumeInfo;
import org.apache.hadoop.ozone.client.rpc.RpcClient;
import org.apache.hadoop.ozone.ksm.KSMConfigKeys;
import org.apache.hadoop.ozone.ksm.helpers.ServiceInfo;
import org.apache.hadoop.ozone.protocol.proto
    .KeySpaceManagerProtocolProtos.ServicePort;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.util.Time;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.entity.InputStreamEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.util.EntityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.ParseException;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.net.HttpURLConnection.HTTP_CREATED;
import static java.net.HttpURLConnection.HTTP_OK;

/**
 * Ozone Client REST protocol implementation. It uses REST protocol to
 * connect to Ozone Handler that executes client calls.
 */
public class RestClient implements ClientProtocol {

  private static final String PATH_SEPARATOR = "/";
  private static final Logger LOG = LoggerFactory.getLogger(RpcClient.class);

  private final Configuration conf;
  private final URI ozoneRestUri;
  private final CloseableHttpClient httpClient;
  private final UserGroupInformation ugi;
  private final OzoneAcl.OzoneACLRights userRights;

   /**
    * Creates RestClient instance with the given configuration.
    * @param conf Configuration
    * @throws IOException
    */
  public RestClient(Configuration conf)
      throws IOException {
    try {
      Preconditions.checkNotNull(conf);
      this.conf = conf;

      long socketTimeout = conf.getTimeDuration(
          OzoneConfigKeys.OZONE_CLIENT_SOCKET_TIMEOUT,
          OzoneConfigKeys.OZONE_CLIENT_SOCKET_TIMEOUT_DEFAULT,
          TimeUnit.MILLISECONDS);
      long connectionTimeout = conf.getTimeDuration(
          OzoneConfigKeys.OZONE_CLIENT_CONNECTION_TIMEOUT,
          OzoneConfigKeys.OZONE_CLIENT_CONNECTION_TIMEOUT_DEFAULT,
          TimeUnit.MILLISECONDS);
      int maxConnection = conf.getInt(
          OzoneConfigKeys.OZONE_REST_CLIENT_HTTP_CONNECTION_MAX,
          OzoneConfigKeys.OZONE_REST_CLIENT_HTTP_CONNECTION_DEFAULT);

      int maxConnectionPerRoute = conf.getInt(
          OzoneConfigKeys.OZONE_REST_CLIENT_HTTP_CONNECTION_PER_ROUTE_MAX,
          OzoneConfigKeys
              .OZONE_REST_CLIENT_HTTP_CONNECTION_PER_ROUTE_MAX_DEFAULT
      );

      /*
      To make RestClient Thread safe, creating the HttpClient with
      ThreadSafeClientConnManager.
      */
      PoolingHttpClientConnectionManager connManager =
          new PoolingHttpClientConnectionManager();
      connManager.setMaxTotal(maxConnection);
      connManager.setDefaultMaxPerRoute(maxConnectionPerRoute);

      this.httpClient = HttpClients.custom()
          .setConnectionManager(connManager)
          .setDefaultRequestConfig(
              RequestConfig.custom()
              .setSocketTimeout(Math.toIntExact(socketTimeout))
                  .setConnectTimeout(Math.toIntExact(connectionTimeout))
                  .build())
          .build();
      this.ugi = UserGroupInformation.getCurrentUser();
      this.userRights = conf.getEnum(KSMConfigKeys.OZONE_KSM_USER_RIGHTS,
          KSMConfigKeys.OZONE_KSM_USER_RIGHTS_DEFAULT);

      // TODO: Add new configuration parameter to configure RestServerSelector.
      RestServerSelector defaultSelector = new DefaultRestServerSelector();
      InetSocketAddress restServer = getOzoneRestServerAddress(defaultSelector);
      URIBuilder uriBuilder = new URIBuilder()
          .setScheme("http")
          .setHost(restServer.getHostName())
          .setPort(restServer.getPort());
      this.ozoneRestUri = uriBuilder.build();

    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
  }

  private InetSocketAddress getOzoneRestServerAddress(
      RestServerSelector selector) throws IOException {
    String httpAddress = conf.get(KSMConfigKeys.OZONE_KSM_HTTP_ADDRESS_KEY);

    if (httpAddress == null) {
      throw new IllegalArgumentException(
          KSMConfigKeys.OZONE_KSM_HTTP_ADDRESS_KEY + " must be defined. See" +
              " https://wiki.apache.org/hadoop/Ozone#Configuration for" +
              " details on configuring Ozone.");
    }

    HttpGet httpGet = new HttpGet("http://" + httpAddress + "/serviceList");
    HttpEntity entity = executeHttpRequest(httpGet);
    try {
      String serviceListJson = EntityUtils.toString(entity);

      ObjectMapper objectMapper = new ObjectMapper();
      TypeReference<List<ServiceInfo>> serviceInfoReference =
          new TypeReference<List<ServiceInfo>>() {
          };
      List<ServiceInfo> services = objectMapper.readValue(
          serviceListJson, serviceInfoReference);

      List<ServiceInfo> dataNodeInfos = services.stream().filter(
          a -> a.getNodeType().equals(HddsProtos.NodeType.DATANODE))
          .collect(Collectors.toList());

      ServiceInfo restServer = selector.getRestServer(dataNodeInfos);

      return NetUtils.createSocketAddr(restServer.getHostname() + ":" +
          restServer.getPort(ServicePort.Type.HTTP));
    } finally {
      EntityUtils.consume(entity);
    }
  }

  @Override
  public void createVolume(String volumeName) throws IOException {
    createVolume(volumeName, VolumeArgs.newBuilder().build());
  }

  @Override
  public void createVolume(String volumeName, VolumeArgs volArgs)
      throws IOException {
    try {
      Preconditions.checkNotNull(volumeName);
      URIBuilder builder = new URIBuilder(ozoneRestUri);
      String owner = volArgs.getOwner() == null ?
          ugi.getUserName() : volArgs.getOwner();
      //TODO: support for ACLs has to be done in OzoneHandler (rest server)
      /**
      List<OzoneAcl> listOfAcls = new ArrayList<>();
      //User ACL
      listOfAcls.add(new OzoneAcl(OzoneAcl.OzoneACLType.USER,
          owner, userRights));
      //ACLs from VolumeArgs
      if(volArgs.getAcls() != null) {
        listOfAcls.addAll(volArgs.getAcls());
      }
       */
      builder.setPath(PATH_SEPARATOR + volumeName);

      String quota = volArgs.getQuota();
      if(quota != null) {
        builder.setParameter(Header.OZONE_QUOTA_QUERY_TAG, quota);
      }

      HttpPost httpPost = new HttpPost(builder.build());
      addOzoneHeaders(httpPost);
      //use admin from VolumeArgs, if it's present
      if(volArgs.getAdmin() != null) {
        httpPost.removeHeaders(HttpHeaders.AUTHORIZATION);
        httpPost.addHeader(HttpHeaders.AUTHORIZATION,
            Header.OZONE_SIMPLE_AUTHENTICATION_SCHEME + " " +
                volArgs.getAdmin());
      }
      httpPost.addHeader(Header.OZONE_USER, owner);
      LOG.info("Creating Volume: {}, with {} as owner and quota set to {}.",
          volumeName, owner, quota == null ? "default" : quota);
      EntityUtils.consume(executeHttpRequest(httpPost));
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
  }


  @Override
  public void setVolumeOwner(String volumeName, String owner)
      throws IOException {
    try {
      Preconditions.checkNotNull(volumeName);
      Preconditions.checkNotNull(owner);
      URIBuilder builder = new URIBuilder(ozoneRestUri);
      builder.setPath(PATH_SEPARATOR + volumeName);
      HttpPut httpPut = new HttpPut(builder.build());
      addOzoneHeaders(httpPut);
      httpPut.addHeader(Header.OZONE_USER, owner);
      EntityUtils.consume(executeHttpRequest(httpPut));
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
  }

  @Override
  public void setVolumeQuota(String volumeName, OzoneQuota quota)
      throws IOException {
    try {
      Preconditions.checkNotNull(volumeName);
      Preconditions.checkNotNull(quota);
      String quotaString = quota.toString();
      URIBuilder builder = new URIBuilder(ozoneRestUri);
      builder.setPath(PATH_SEPARATOR + volumeName);
      builder.setParameter(Header.OZONE_QUOTA_QUERY_TAG, quotaString);
      HttpPut httpPut = new HttpPut(builder.build());
      addOzoneHeaders(httpPut);
      EntityUtils.consume(executeHttpRequest(httpPut));
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
  }

  @Override
  public OzoneVolume getVolumeDetails(String volumeName)
      throws IOException {
    try {
      Preconditions.checkNotNull(volumeName);
      URIBuilder builder = new URIBuilder(ozoneRestUri);
      builder.setPath(PATH_SEPARATOR + volumeName);
      builder.setParameter(Header.OZONE_INFO_QUERY_TAG,
          Header.OZONE_INFO_QUERY_VOLUME);
      HttpGet httpGet = new HttpGet(builder.build());
      addOzoneHeaders(httpGet);
      HttpEntity response = executeHttpRequest(httpGet);
      VolumeInfo volInfo =
          VolumeInfo.parse(EntityUtils.toString(response));
      //TODO: OzoneHandler in datanode has to be modified to send ACLs
      OzoneVolume volume = new OzoneVolume(conf,
          this,
          volInfo.getVolumeName(),
          volInfo.getCreatedBy(),
          volInfo.getOwner().getName(),
          volInfo.getQuota().sizeInBytes(),
          HddsClientUtils.formatDateTime(volInfo.getCreatedOn()),
          null);
      EntityUtils.consume(response);
      return volume;
    } catch (URISyntaxException | ParseException e) {
      throw new IOException(e);
    }
  }

  @Override
  public boolean checkVolumeAccess(String volumeName, OzoneAcl acl)
      throws IOException {
    throw new UnsupportedOperationException("Not yet implemented.");
  }

  @Override
  public void deleteVolume(String volumeName) throws IOException {
    try {
      Preconditions.checkNotNull(volumeName);
      URIBuilder builder = new URIBuilder(ozoneRestUri);
      builder.setPath(PATH_SEPARATOR + volumeName);
      HttpDelete httpDelete = new HttpDelete(builder.build());
      addOzoneHeaders(httpDelete);
      EntityUtils.consume(executeHttpRequest(httpDelete));
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
  }

  @Override
  public List<OzoneVolume> listVolumes(String volumePrefix, String prevKey,
                                       int maxListResult)
      throws IOException {
    throw new UnsupportedOperationException("Not yet implemented.");
  }

  @Override
  public List<OzoneVolume> listVolumes(String user, String volumePrefix,
                                       String prevKey, int maxListResult)
      throws IOException {
    throw new UnsupportedOperationException("Not yet implemented.");
  }

  @Override
  public void createBucket(String volumeName, String bucketName)
      throws IOException {
    createBucket(volumeName, bucketName, BucketArgs.newBuilder().build());
  }

  @Override
  public void createBucket(
      String volumeName, String bucketName, BucketArgs bucketArgs)
      throws IOException {
    try {
      Preconditions.checkNotNull(volumeName);
      Preconditions.checkNotNull(bucketName);
      Preconditions.checkNotNull(bucketArgs);
      URIBuilder builder = new URIBuilder(ozoneRestUri);
      OzoneConsts.Versioning versioning = OzoneConsts.Versioning.DISABLED;
      if(bucketArgs.getVersioning() != null &&
          bucketArgs.getVersioning()) {
        versioning = OzoneConsts.Versioning.ENABLED;
      }
      StorageType storageType = bucketArgs.getStorageType() == null ?
          StorageType.DEFAULT : bucketArgs.getStorageType();

      builder.setPath(PATH_SEPARATOR + volumeName +
          PATH_SEPARATOR + bucketName);
      HttpPost httpPost = new HttpPost(builder.build());
      addOzoneHeaders(httpPost);

      //ACLs from BucketArgs
      if(bucketArgs.getAcls() != null) {
        for (OzoneAcl acl : bucketArgs.getAcls()) {
          httpPost.addHeader(
              Header.OZONE_ACLS, Header.OZONE_ACL_ADD + " " + acl.toString());
        }
      }
      httpPost.addHeader(Header.OZONE_STORAGE_TYPE, storageType.toString());
      httpPost.addHeader(Header.OZONE_BUCKET_VERSIONING,
          versioning.toString());
      LOG.info("Creating Bucket: {}/{}, with Versioning {} and Storage Type" +
              " set to {}", volumeName, bucketName, versioning,
          storageType);

      EntityUtils.consume(executeHttpRequest(httpPost));
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
  }

  @Override
  public void addBucketAcls(
      String volumeName, String bucketName, List<OzoneAcl> addAcls)
      throws IOException {
    try {
      Preconditions.checkNotNull(volumeName);
      Preconditions.checkNotNull(bucketName);
      Preconditions.checkNotNull(addAcls);
      URIBuilder builder = new URIBuilder(ozoneRestUri);

      builder.setPath(PATH_SEPARATOR + volumeName +
          PATH_SEPARATOR + bucketName);
      HttpPut httpPut = new HttpPut(builder.build());
      addOzoneHeaders(httpPut);

      for (OzoneAcl acl : addAcls) {
        httpPut.addHeader(
            Header.OZONE_ACLS, Header.OZONE_ACL_ADD + " " + acl.toString());
      }
      EntityUtils.consume(executeHttpRequest(httpPut));
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
  }

  @Override
  public void removeBucketAcls(
      String volumeName, String bucketName, List<OzoneAcl> removeAcls)
      throws IOException {
    try {
      Preconditions.checkNotNull(volumeName);
      Preconditions.checkNotNull(bucketName);
      Preconditions.checkNotNull(removeAcls);
      URIBuilder builder = new URIBuilder(ozoneRestUri);

      builder.setPath(PATH_SEPARATOR + volumeName +
          PATH_SEPARATOR + bucketName);
      HttpPut httpPut = new HttpPut(builder.build());
      addOzoneHeaders(httpPut);

      for (OzoneAcl acl : removeAcls) {
        httpPut.addHeader(
            Header.OZONE_ACLS, Header.OZONE_ACL_REMOVE + " " + acl.toString());
      }
      EntityUtils.consume(executeHttpRequest(httpPut));
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
  }

  @Override
  public void setBucketVersioning(
      String volumeName, String bucketName, Boolean versioning)
      throws IOException {
    try {
      Preconditions.checkNotNull(volumeName);
      Preconditions.checkNotNull(bucketName);
      Preconditions.checkNotNull(versioning);
      URIBuilder builder = new URIBuilder(ozoneRestUri);

      builder.setPath(PATH_SEPARATOR + volumeName +
          PATH_SEPARATOR + bucketName);
      HttpPut httpPut = new HttpPut(builder.build());
      addOzoneHeaders(httpPut);

      httpPut.addHeader(Header.OZONE_BUCKET_VERSIONING,
          getBucketVersioning(versioning).toString());
      EntityUtils.consume(executeHttpRequest(httpPut));
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
  }

  @Override
  public void setBucketStorageType(
      String volumeName, String bucketName, StorageType storageType)
      throws IOException {
    try {
      Preconditions.checkNotNull(volumeName);
      Preconditions.checkNotNull(bucketName);
      Preconditions.checkNotNull(storageType);
      URIBuilder builder = new URIBuilder(ozoneRestUri);

      builder.setPath(PATH_SEPARATOR + volumeName +
          PATH_SEPARATOR + bucketName);
      HttpPut httpPut = new HttpPut(builder.build());
      addOzoneHeaders(httpPut);

      httpPut.addHeader(Header.OZONE_STORAGE_TYPE, storageType.toString());
      EntityUtils.consume(executeHttpRequest(httpPut));
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
  }

  @Override
  public void deleteBucket(String volumeName, String bucketName)
      throws IOException {
    try {
      Preconditions.checkNotNull(volumeName);
      Preconditions.checkNotNull(bucketName);
      URIBuilder builder = new URIBuilder(ozoneRestUri);
      builder.setPath(PATH_SEPARATOR + volumeName +
          PATH_SEPARATOR + bucketName);
      HttpDelete httpDelete = new HttpDelete(builder.build());
      addOzoneHeaders(httpDelete);
      EntityUtils.consume(executeHttpRequest(httpDelete));
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
  }

  @Override
  public void checkBucketAccess(String volumeName, String bucketName)
      throws IOException {
    throw new UnsupportedOperationException("Not yet implemented.");
  }

  @Override
  public OzoneBucket getBucketDetails(String volumeName, String bucketName)
      throws IOException {
    try {
      Preconditions.checkNotNull(volumeName);
      Preconditions.checkNotNull(bucketName);
      URIBuilder builder = new URIBuilder(ozoneRestUri);
      builder.setPath(PATH_SEPARATOR + volumeName +
          PATH_SEPARATOR + bucketName);
      builder.setParameter(Header.OZONE_INFO_QUERY_TAG,
          Header.OZONE_INFO_QUERY_BUCKET);
      HttpGet httpGet = new HttpGet(builder.build());
      addOzoneHeaders(httpGet);
      HttpEntity response = executeHttpRequest(httpGet);
      BucketInfo bucketInfo =
          BucketInfo.parse(EntityUtils.toString(response));
      OzoneBucket bucket = new OzoneBucket(conf,
          this,
          bucketInfo.getVolumeName(),
          bucketInfo.getBucketName(),
          bucketInfo.getAcls(),
          bucketInfo.getStorageType(),
          getBucketVersioningFlag(bucketInfo.getVersioning()),
          HddsClientUtils.formatDateTime(bucketInfo.getCreatedOn()));
      EntityUtils.consume(response);
      return bucket;
    } catch (URISyntaxException | ParseException e) {
      throw new IOException(e);
    }
  }

  @Override
  public List<OzoneBucket> listBuckets(String volumeName, String bucketPrefix,
                                       String prevBucket, int maxListResult)
      throws IOException {
    throw new UnsupportedOperationException("Not yet implemented.");
  }

  /**
   * Writes a key in an existing bucket.
   *
   * @param volumeName Name of the Volume
   * @param bucketName Name of the Bucket
   * @param keyName Name of the Key
   * @param size Size of the data
   * @param type
   * @param factor @return {@link OzoneOutputStream}
   */
  @Override
  public OzoneOutputStream createKey(
      String volumeName, String bucketName, String keyName, long size,
      ReplicationType type, ReplicationFactor factor)
      throws IOException {
    // TODO: Once ReplicationType and ReplicationFactor are supported in
    // OzoneHandler (in Datanode), set them in header.
    try {
      Preconditions.checkNotNull(volumeName);
      Preconditions.checkNotNull(bucketName);
      Preconditions.checkNotNull(keyName);
      URIBuilder builder = new URIBuilder(ozoneRestUri);
      builder.setPath(PATH_SEPARATOR + volumeName +
          PATH_SEPARATOR + bucketName +
          PATH_SEPARATOR + keyName);
      HttpPut putRequest = new HttpPut(builder.build());
      addOzoneHeaders(putRequest);
      PipedInputStream in = new PipedInputStream();
      OutputStream out = new PipedOutputStream(in);
      putRequest.setEntity(new InputStreamEntity(in, size));
      FutureTask<HttpEntity> futureTask =
          new FutureTask<>(() -> executeHttpRequest(putRequest));
      new Thread(futureTask).start();
      OzoneOutputStream outputStream = new OzoneOutputStream(
          new OutputStream() {
            @Override
            public void write(int b) throws IOException {
              out.write(b);
            }

            @Override
            public void close() throws IOException {
              try {
                out.close();
                EntityUtils.consume(futureTask.get());
              } catch (ExecutionException | InterruptedException e) {
                throw new IOException(e);
              }
            }
          });

      return outputStream;
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
  }

  @Override
  public OzoneInputStream getKey(
      String volumeName, String bucketName, String keyName)
      throws IOException {
    try {
      Preconditions.checkNotNull(volumeName);
      Preconditions.checkNotNull(bucketName);
      Preconditions.checkNotNull(keyName);
      URIBuilder builder = new URIBuilder(ozoneRestUri);
      builder.setPath(PATH_SEPARATOR + volumeName +
          PATH_SEPARATOR + bucketName +
          PATH_SEPARATOR + keyName);
      HttpGet getRequest = new HttpGet(builder.build());
      addOzoneHeaders(getRequest);
      HttpEntity entity = executeHttpRequest(getRequest);
      PipedInputStream in = new PipedInputStream();
      OutputStream out = new PipedOutputStream(in);
      FutureTask<Void> futureTask =
          new FutureTask<>(() -> {
            entity.writeTo(out);
            out.close();
            return null;
          });
      new Thread(futureTask).start();
      OzoneInputStream inputStream = new OzoneInputStream(
          new InputStream() {

            @Override
            public int read() throws IOException {
              return in.read();
            }

            @Override
            public void close() throws IOException {
              in.close();
              EntityUtils.consume(entity);
            }
          });

      return inputStream;
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
  }

  @Override
  public void deleteKey(String volumeName, String bucketName, String keyName)
      throws IOException {
    try {
      Preconditions.checkNotNull(volumeName);
      Preconditions.checkNotNull(bucketName);
      Preconditions.checkNotNull(keyName);
      URIBuilder builder = new URIBuilder(ozoneRestUri);
      builder.setPath(PATH_SEPARATOR + volumeName +
          PATH_SEPARATOR + bucketName + PATH_SEPARATOR + keyName);
      HttpDelete httpDelete = new HttpDelete(builder.build());
      addOzoneHeaders(httpDelete);
      EntityUtils.consume(executeHttpRequest(httpDelete));
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
  }

  @Override
  public List<OzoneKey> listKeys(String volumeName, String bucketName,
                                 String keyPrefix, String prevKey,
                                 int maxListResult)
      throws IOException {
    throw new UnsupportedOperationException("Not yet implemented.");
  }

  @Override
  public OzoneKey getKeyDetails(
      String volumeName, String bucketName, String keyName)
      throws IOException {
    try {
      Preconditions.checkNotNull(volumeName);
      Preconditions.checkNotNull(bucketName);
      Preconditions.checkNotNull(keyName);
      URIBuilder builder = new URIBuilder(ozoneRestUri);
      builder.setPath(PATH_SEPARATOR + volumeName +
          PATH_SEPARATOR + bucketName + PATH_SEPARATOR + keyName);
      builder.setParameter(Header.OZONE_INFO_QUERY_TAG,
          Header.OZONE_INFO_QUERY_KEY);
      HttpGet httpGet = new HttpGet(builder.build());
      addOzoneHeaders(httpGet);
      HttpEntity response = executeHttpRequest(httpGet);
      KeyInfo keyInfo =
          KeyInfo.parse(EntityUtils.toString(response));
      OzoneKey key = new OzoneKey(volumeName,
          bucketName,
          keyInfo.getKeyName(),
          keyInfo.getSize(),
          HddsClientUtils.formatDateTime(keyInfo.getCreatedOn()),
          HddsClientUtils.formatDateTime(keyInfo.getModifiedOn()));
      EntityUtils.consume(response);
      return key;
    } catch (URISyntaxException | ParseException e) {
      throw new IOException(e);
    }
  }

  /**
   * Adds Ozone headers to http request.
   *
   * @param httpRequest Http Request
   */
  private void addOzoneHeaders(HttpUriRequest httpRequest) {
    httpRequest.addHeader(HttpHeaders.AUTHORIZATION,
        Header.OZONE_SIMPLE_AUTHENTICATION_SCHEME + " " +
            ugi.getUserName());
    httpRequest.addHeader(HttpHeaders.DATE,
        HddsClientUtils.formatDateTime(Time.monotonicNow()));
    httpRequest.addHeader(Header.OZONE_VERSION_HEADER,
        Header.OZONE_V1_VERSION_HEADER);
  }

  /**
   * Sends the http request to server and returns the response HttpEntity.
   * It's responsibility of the caller to consume and close response HttpEntity
   * by calling {@code EntityUtils.consume}
   *
   * @param httpUriRequest http request
   * @throws IOException
   */
  private HttpEntity executeHttpRequest(HttpUriRequest httpUriRequest)
      throws IOException {
    HttpResponse response = httpClient.execute(httpUriRequest);
    int errorCode = response.getStatusLine().getStatusCode();
    HttpEntity entity = response.getEntity();
    if ((errorCode == HTTP_OK) || (errorCode == HTTP_CREATED)) {
      return entity;
    }
    if (entity != null) {
      throw new IOException(
          OzoneException.parse(EntityUtils.toString(entity)));
    } else {
      throw new IOException("Unexpected null in http payload," +
          " while processing request");
    }
  }

  /**
   * Converts OzoneConts.Versioning to boolean.
   *
   * @param version
   * @return corresponding boolean value
   */
  private Boolean getBucketVersioningFlag(
      OzoneConsts.Versioning version) {
    if(version != null) {
      switch(version) {
      case ENABLED:
        return true;
      case NOT_DEFINED:
      case DISABLED:
      default:
        return false;
      }
    }
    return false;
  }

  /**
   * Converts Bucket versioning flag into OzoneConts.Versioning.
   *
   * @param flag versioning flag
   * @return corresponding OzoneConts.Versionin
   */
  private OzoneConsts.Versioning getBucketVersioning(Boolean flag) {
    if(flag != null) {
      if(flag) {
        return OzoneConsts.Versioning.ENABLED;
      } else {
        return OzoneConsts.Versioning.DISABLED;
      }
    }
    return OzoneConsts.Versioning.NOT_DEFINED;
  }

  @Override
  public void close() throws IOException {
    httpClient.close();
  }
}
