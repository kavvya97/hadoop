/*
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

package org.apache.hadoop.fs.s3a;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.fs.s3a.impl.PutObjectOptions;
import org.apache.hadoop.fs.store.audit.AuditSpan;
import org.apache.hadoop.io.IOUtils;

import org.junit.Assert;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static org.apache.hadoop.fs.contract.ContractTestUtils.dataset;
import static org.apache.hadoop.fs.s3a.Invoker.LOG_EVENT;

/**
 * Utilities for S3A multipart upload tests.
 */
public final class MultipartTestUtils {
  private static final Logger LOG = LoggerFactory.getLogger(
      MultipartTestUtils.class);

  /** Not instantiated. */
  private MultipartTestUtils() { }

  /**
   * Clean up all provided uploads.
   * @param keySet set of uploads to abort
   */
  static void cleanupParts(S3AFileSystem fs, Set <IdKey> keySet) {
    boolean anyFailure = false;
    for (IdKey ik : keySet) {
      try (AuditSpan span =
               fs.createSpan("multipart", ik.key, null)) {

        LOG.debug("aborting upload id {}", ik.getUploadId());
        fs.abortMultipartUpload(ik.getKey(), ik.getUploadId());
      } catch (Exception e) {
        LOG.error(String.format("Failure aborting upload %s, continuing.",
            ik.getKey()), e);
        anyFailure = true;
      }
    }
    Assert.assertFalse("Failure aborting multipart upload(s), see log.",
        anyFailure);
  }

  public static IdKey createPartUpload(S3AFileSystem fs, String key, int len,
      int partNo) throws IOException {
    try (AuditSpan span = fs.createSpan("multipart", key, null)) {
      WriteOperationHelper writeHelper = fs.getWriteOperationHelper();
      byte[] data = dataset(len, 'a', 'z');
      InputStream in = new ByteArrayInputStream(data);
      String uploadId = writeHelper.initiateMultiPartUpload(key, PutObjectOptions.keepingDirs());
      UploadPartRequest req = writeHelper.newUploadPartRequestBuilder(key, uploadId,
          partNo, len).build();
      RequestBody body = RequestBody.fromInputStream(in, len);
      UploadPartResponse response = writeHelper.uploadPart(req, body, null);
      LOG.debug("uploaded part etag {}, upid {}", response.eTag(), uploadId);
      return new IdKey(key, uploadId);
    }
  }

  /** Delete any uploads under given path (recursive).  Silent on failure. */
  public static void clearAnyUploads(S3AFileSystem fs, Path path) {
    String key = fs.pathToKey(path);
    AuditSpan span = null;
    try {
      RemoteIterator<MultipartUpload> uploads = fs.listUploads(key);
      span = fs.createSpan("multipart", path.toString(), null);
      final WriteOperationHelper helper
          = fs.getWriteOperationHelper();
      while (uploads.hasNext()) {
        MultipartUpload upload = uploads.next();
        LOG.debug("Cleaning up upload: {} {}", upload.key(),
            truncatedUploadId(upload.uploadId()));
        helper.abortMultipartUpload(upload.key(),
            upload.uploadId(), true, LOG_EVENT);
      }
    } catch (IOException ioe) {
      LOG.info("Ignoring exception: ", ioe);
    } finally {
      IOUtils.closeStream(span);
    }
  }

  /** Assert that there are not any upload parts at given path. */
  public static void assertNoUploadsAt(S3AFileSystem fs, Path path) throws
      Exception {
    String key = fs.pathToKey(path);
    RemoteIterator<MultipartUpload> uploads = fs.listUploads(key);
    while (uploads.hasNext()) {
      MultipartUpload upload = uploads.next();
      Assert.fail("Found unexpected upload " + upload.key() + " " +
          truncatedUploadId(upload.uploadId()));
    }
  }

  /** Get number of part uploads under given path. */
  public static int countUploadsAt(S3AFileSystem fs, Path path) throws
      IOException {
    String key = fs.pathToKey(path);
    RemoteIterator<MultipartUpload> uploads = fs.listUploads(key);
    int count = 0;
    while (uploads.hasNext()) {
      MultipartUpload upload = uploads.next();
      count++;
    }
    return count;
  }

  /**
   * Get a list of all pending uploads under a prefix, one which can be printed.
   * @param prefix prefix to look under
   * @return possibly empty list
   * @throws IOException IO failure.
   */
  public static List<String> listMultipartUploads(S3AFileSystem fs,
      String prefix) throws IOException {

    try (AuditSpan span = fs.createSpan("multipart", prefix, null)) {
      return fs
          .listMultipartUploads(prefix).stream()
          .map(upload -> String.format("Upload to %s with ID %s; initiated %s",
              upload.key(),
              upload.uploadId(),
              S3ATestUtils.LISTING_FORMAT.format(Date.from(upload.initiated()))))
          .collect(Collectors.toList());
    }
  }


  private static String truncatedUploadId(String fullId) {
    return fullId.substring(0, 12) + " ...";
  }

  /** Struct of object key, upload ID. */
  public static class IdKey {
    private String key;
    private String uploadId;

    IdKey(String key, String uploadId) {
      this.key = key;
      this.uploadId = uploadId;
    }

    public String getKey() {
      return key;
    }

    public String getUploadId() {
      return uploadId;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      IdKey key1 = (IdKey) o;
      return Objects.equals(key, key1.key) &&
          Objects.equals(uploadId, key1.uploadId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(key, uploadId);
    }

    @Override
    public String toString() {
      return "IdKey{" +
          "key='" + key + '\'' +
          ", uploadId='" + uploadId + '\'' +
          '}';
    }
  }
}
