/**
 * Copyright (C) 2015 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.fabric8.kubernetes.client.mock;

import io.fabric8.kubernetes.api.model.Status;
import io.fabric8.kubernetes.api.model.StatusBuilder;
import io.fabric8.kubernetes.client.ResourceNotFoundException;
import io.fabric8.kubernetes.client.dsl.base.WaitForConditionWatcher;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.migrationsupport.rules.EnableRuleMigrationSupport;

import java.net.HttpURLConnection;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.WatchEvent;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.Watch;
import io.fabric8.kubernetes.client.Watcher;
import io.fabric8.kubernetes.client.internal.readiness.Readiness;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;

import static org.assertj.core.api.Assertions.*;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

@EnableRuleMigrationSupport
public class ResourceTest {

  @Rule
  public KubernetesServer server = new KubernetesServer();


    @Test
    public void testCreateOrReplace() {
        Pod pod1 = new PodBuilder().withNewMetadata().withName("pod1").withNamespace("test").and().build();

        server.expect().get().withPath("/api/v1/namespaces/test/pods/pod1").andReturn(404, "").once();
        server.expect().post().withPath("/api/v1/namespaces/test/pods").andReturn(201, pod1).once();

        KubernetesClient client = server.getClient();
        HasMetadata response = client.resource(pod1).createOrReplace();
        assertEquals(pod1, response);
    }

    @Test
    public void testCreateWithExplicitNamespace() {
        Pod pod1 = new PodBuilder().withNewMetadata().withName("pod1").withNamespace("test").and().build();

        server.expect().get().withPath("/api/v1/namespaces/ns1/pods/pod1").andReturn(404, "").once();
        server.expect().post().withPath("/api/v1/namespaces/ns1/pods").andReturn(201, pod1).once();

        KubernetesClient client = server.getClient();
        HasMetadata response = client.resource(pod1).inNamespace("ns1").apply();
        assertEquals(pod1, response);
    }

    @Test
    public void testCreateOrReplaceWithDeleteExisting() throws Exception {
      Pod pod1 = new PodBuilder().withNewMetadata().withName("pod1").withNamespace("test").and().build();

      server.expect().get().withPath("/api/v1/namespaces/ns1/pods/pod1").andReturn(200, pod1).once();
      server.expect().delete().withPath("/api/v1/namespaces/ns1/pods/pod1").andReturn(200, pod1).once();
      server.expect().post().withPath("/api/v1/namespaces/ns1/pods").andReturn(201, pod1).once();

      KubernetesClient client = server.getClient();
      HasMetadata response = client.resource(pod1).inNamespace("ns1").deletingExisting().createOrReplace();
      assertEquals(pod1, response);

      RecordedRequest request = server.getLastRequest();
      assertEquals(3, server.getMockServer().getRequestCount());
      assertEquals("/api/v1/namespaces/ns1/pods", request.getPath());
      assertEquals("POST", request.getMethod());
    }

    @Test
    public void testRequire() {
      Assertions.assertThrows(ResourceNotFoundException.class, () -> {
        Pod pod1 = new PodBuilder().withNewMetadata().withName("pod1").withNamespace("test1").and().build();

        server.expect().get().withPath("/api/v1/namespaces/ns1/pods/pod1").andReturn(HttpURLConnection.HTTP_NOT_FOUND, "").once();

        KubernetesClient client = server.getClient();
        client.pods().inNamespace("ns1").withName("pod1").require();
      });
    }

  @Test
  public void testDelete() {
    Pod pod1 = new PodBuilder().withNewMetadata().withName("pod1").withNamespace("test").and().build();
    Pod pod2 = new PodBuilder().withNewMetadata().withName("pod2").withNamespace("ns1").and().build();
    Pod pod3 = new PodBuilder().withNewMetadata().withName("pod3").withNamespace("any").and().build();

   server.expect().withPath("/api/v1/namespaces/test/pods/pod1").andReturn(200, pod1).once();
   server.expect().withPath("/api/v1/namespaces/ns1/pods/pod2").andReturn(200, pod2).once();

    KubernetesClient client = server.getClient();
    Boolean deleted = client.resource(pod1).delete();
    assertTrue(deleted);
    deleted = client.resource(pod2).delete();
    assertTrue(deleted);

    deleted = client.resource(pod3).delete();
    assertFalse(deleted);
  }


  @Test
  public void testWatch() throws InterruptedException {
    Pod pod1 = new PodBuilder().withNewMetadata()
        .withName("pod1")
        .withResourceVersion("1")
      .withNamespace("test").and().build();

      server.expect().get().withPath("/api/v1/namespaces/test/pods").andReturn(200, pod1).once();
      server.expect().post().withPath("/api/v1/namespaces/test/pods").andReturn(201, pod1).once();

     server.expect().get().withPath("/api/v1/namespaces/test/pods?fieldSelector=metadata.name%3Dpod1&watch=true").andUpgradeToWebSocket()
        .open()
          .waitFor(1000).andEmit(new WatchEvent(pod1, "DELETED"))
        .done()
      .always();

    KubernetesClient client = server.getClient();
    final CountDownLatch latch = new CountDownLatch(1);

    Watch watch = client.resource(pod1).watch(new Watcher<Pod>() {
      @Override
      public void eventReceived(Action action, Pod resource) {
        latch.countDown();
      }

      @Override
      public void onClose(KubernetesClientException cause) {

      }
    });
    Assert.assertTrue(latch.await(5000, TimeUnit.MILLISECONDS));
    watch.close();
  }


  @Test
  public void testWaitUntilReady() throws InterruptedException {
    Pod pod1 = new PodBuilder().withNewMetadata()
      .withName("pod1")
      .withResourceVersion("1")
      .withNamespace("test").and().build();


    Pod noReady = new PodBuilder(pod1).withNewStatus()
      .addNewCondition()
          .withType("Ready")
          .withStatus("False")
        .endCondition()
      .endStatus()
      .build();

    Pod ready = new PodBuilder(pod1).withNewStatus()
        .addNewCondition()
          .withType("Ready")
          .withStatus("True")
        .endCondition()
      .endStatus()
      .build();

    server.expect().get().withPath("/api/v1/namespaces/test/pods/pod1").andReturn(200, noReady).once();

    server.expect().get().withPath("/api/v1/namespaces/test/pods?fieldSelector=metadata.name%3Dpod1&resourceVersion=1&watch=true").andUpgradeToWebSocket()
      .open()
      .waitFor(500).andEmit(new WatchEvent(ready, "MODIFIED"))
      .done()
      .always();

    KubernetesClient client = server.getClient();
    Pod p = client.resource(noReady).waitUntilReady(5, TimeUnit.SECONDS);
    Assert.assertTrue(Readiness.isPodReady(p));
  }

  @Test
  public void testWaitUntilExistsThenReady() throws InterruptedException {
    Pod pod1 = new PodBuilder().withNewMetadata()
      .withName("pod1")
      .withResourceVersion("1")
      .withNamespace("test").and().build();


    Pod noReady = new PodBuilder(pod1).withNewStatus()
      .addNewCondition()
          .withType("Ready")
          .withStatus("False")
        .endCondition()
      .endStatus()
      .build();

    Pod ready = new PodBuilder(pod1).withNewStatus()
        .addNewCondition()
          .withType("Ready")
          .withStatus("True")
        .endCondition()
      .endStatus()
      .build();

    // so that "waitUntilExists" actually has some waiting to do
    server.expect().get().withPath("/api/v1/namespaces/test/pods/pod1").andReturn(404, "").times(2);
    // once so that "waitUntilExists" successfully ends
    // and again so that "periodicWatchUntilReady" successfully begins
    server.expect().get().withPath("/api/v1/namespaces/test/pods/pod1").andReturn(200, noReady).times(2);

    server.expect().get().withPath("/api/v1/namespaces/test/pods?fieldSelector=metadata.name%3Dpod1&watch=true").andUpgradeToWebSocket()
      .open()
      .waitFor(100).andEmit(new WatchEvent(ready, "MODIFIED"))
      .done()
      .always();

    KubernetesClient client = server.getClient();
    Pod p = client.pods().withName("pod1").waitUntilReady(5, TimeUnit.SECONDS);
    Assert.assertTrue(Readiness.isPodReady(p));
  }

  @Test
  public void testWaitUntilCondition() throws InterruptedException {
    Pod pod1 = new PodBuilder().withNewMetadata()
      .withName("pod1")
      .withResourceVersion("1")
      .withNamespace("test").and().build();


    Pod noReady = new PodBuilder(pod1).withNewStatus()
      .addNewCondition()
      .withType("Ready")
      .withStatus("False")
      .endCondition()
      .endStatus()
      .build();

    Pod ready = new PodBuilder(pod1).withNewStatus()
      .addNewCondition()
      .withType("Ready")
      .withStatus("True")
      .endCondition()
      .endStatus()
      .build();

    Pod withConditionBeingFalse = new PodBuilder(pod1).withNewStatus()
      .addNewCondition()
      .withType("Ready")
      .withStatus("True")
      .endCondition()
      .addNewCondition()
      .withType("dummy")
      .withStatus("False")
      .endCondition()
      .endStatus()
      .build();

    Pod withConditionBeingTrue = new PodBuilder(pod1).withNewStatus()
      .addNewCondition()
      .withType("Ready")
      .withStatus("True")
      .endCondition()
      .addNewCondition()
      .withType("Dummy")
      .withStatus("True")
      .endCondition()
      .endStatus()
      .build();

    // at first the pod is non-ready
    server.expect().get().withPath("/api/v1/namespaces/test/pods/pod1").andReturn(200, noReady).times(2);

    server.expect().get().withPath("/api/v1/namespaces/test/pods?fieldSelector=metadata.name%3Dpod1&resourceVersion=1&watch=true").andUpgradeToWebSocket()
      .open()
      .waitFor(1000).andEmit(new WatchEvent(ready, "MODIFIED"))
      .waitFor(2000).andEmit(new WatchEvent(withConditionBeingFalse, "MODIFIED"))
      .waitFor(2500).andEmit(new WatchEvent(withConditionBeingTrue, "MODIFIED"))
      .done()
      .always();

    KubernetesClient client = server.getClient();
    Pod p = client.pods().withName("pod1").waitUntilCondition(
      r -> r.getStatus().getConditions()
            .stream()
            .anyMatch(c -> "Dummy".equals(c.getType()) && "True".equals(c.getStatus())),
      8, TimeUnit.SECONDS
    );

    assertThat(p.getStatus().getConditions())
      .extracting("type", "status")
      .containsExactly(tuple("Ready", "True"), tuple("Dummy", "True"));
  }

  @Test
  void testRetryOnErrorEventDuringWaitReturnFromAPIIfMatch() throws InterruptedException {
    Pod pod1 = new PodBuilder().withNewMetadata()
      .withName("pod1")
      .withResourceVersion("1")
      .withNamespace("test").and().build();


    Pod noReady = new PodBuilder(pod1).withNewStatus()
      .addNewCondition()
      .withType("Ready")
      .withStatus("False")
      .endCondition()
      .endStatus()
      .build();

    Pod ready = new PodBuilder(pod1).withNewStatus()
      .addNewCondition()
      .withType("Ready")
      .withStatus("True")
      .endCondition()
      .endStatus()
      .build();

    Status status = new StatusBuilder()
      .withCode(HttpURLConnection.HTTP_FORBIDDEN)
      .build();

    // once not ready, to begin watch
    server.expect().get().withPath("/api/v1/namespaces/test/pods/pod1").andReturn(200, noReady).once();
    // once ready, after watch fails
    server.expect().get().withPath("/api/v1/namespaces/test/pods/pod1").andReturn(200, ready).once();

    server.expect().get().withPath("/api/v1/namespaces/test/pods?fieldSelector=metadata.name%3Dpod1&resourceVersion=1&watch=true").andUpgradeToWebSocket()
      .open()
      .waitFor(500).andEmit(new WatchEvent(status, "ERROR"))
      .done()
      .once();

    KubernetesClient client = server.getClient();
    Pod p = client.resource(noReady).waitUntilReady(5, TimeUnit.SECONDS);
    Assert.assertTrue(Readiness.isPodReady(p));
  }

  @Test
  void testRetryOnErrorEventDuringWait() throws InterruptedException {
    Pod pod1 = new PodBuilder().withNewMetadata()
      .withName("pod1")
      .withResourceVersion("1")
      .withNamespace("test").and().build();

    Pod noReady = new PodBuilder(pod1).withNewStatus()
      .addNewCondition()
      .withType("Ready")
      .withStatus("False")
      .endCondition()
      .endStatus()
      .build();

    Pod ready = new PodBuilder(pod1).withNewStatus()
      .addNewCondition()
      .withType("Ready")
      .withStatus("True")
      .endCondition()
      .endStatus()
      .build();

    Status status = new StatusBuilder()
      .withCode(HttpURLConnection.HTTP_FORBIDDEN)
      .build();

    // once not ready, to begin watch
    server.expect().get().withPath("/api/v1/namespaces/test/pods/pod1").andReturn(200, noReady).times(2);

    server.expect().get().withPath("/api/v1/namespaces/test/pods?fieldSelector=metadata.name%3Dpod1&resourceVersion=1&watch=true").andUpgradeToWebSocket()
      .open()
      .waitFor(500).andEmit(new WatchEvent(status, "ERROR"))
      .done()
      .once();

    server.expect().get().withPath("/api/v1/namespaces/test/pods?fieldSelector=metadata.name%3Dpod1&resourceVersion=1&watch=true").andUpgradeToWebSocket()
      .open()
      .waitFor(500).andEmit(new WatchEvent(ready, "MODIFIED"))
      .done()
      .once();

    KubernetesClient client = server.getClient();
    Pod p = client.resource(noReady).waitUntilReady(5, TimeUnit.SECONDS);
    Assert.assertTrue(Readiness.isPodReady(p));
  }

  @Test
  void testSkipWatchIfAlreadyMatchingCondition() throws InterruptedException {
    Pod pod1 = new PodBuilder().withNewMetadata()
      .withName("pod1")
      .withResourceVersion("1")
      .withNamespace("test").and().build();


    Pod noReady = new PodBuilder(pod1).withNewStatus()
      .addNewCondition()
      .withType("Ready")
      .withStatus("False")
      .endCondition()
      .endStatus()
      .build();

    Pod ready = new PodBuilder(pod1).withNewStatus()
      .addNewCondition()
      .withType("Ready")
      .withStatus("True")
      .endCondition()
      .endStatus()
      .build();

    // once not ready, to begin watch
    server.expect().get().withPath("/api/v1/namespaces/test/pods/pod1").andReturn(200, ready).once();

    KubernetesClient client = server.getClient();
    Pod p = client.resource(noReady).waitUntilReady(5, TimeUnit.SECONDS);
    Assert.assertTrue(Readiness.isPodReady(p));
  }

  @Test
  void testDontRetryWatchOnHttpGone() throws InterruptedException {
    Pod noReady = new PodBuilder().withNewMetadata()
      .withName("pod1")
      .withResourceVersion("1")
      .withNamespace("test").and().withNewStatus()
      .addNewCondition()
      .withType("Ready")
      .withStatus("False")
      .endCondition()
      .endStatus()
      .build();

    Status status = new StatusBuilder()
      .withCode(HttpURLConnection.HTTP_GONE)
      .build();

    // once not ready, to begin watch
    server.expect().get().withPath("/api/v1/namespaces/test/pods/pod1").andReturn(200, noReady).once();

    server.expect().get().withPath("/api/v1/namespaces/test/pods?fieldSelector=metadata.name%3Dpod1&resourceVersion=1&watch=true").andUpgradeToWebSocket()
      .open()
      .waitFor(500).andEmit(new WatchEvent(status, "ERROR"))
      .done()
      .once();

    KubernetesClient client = server.getClient();
    try {
      client.resource(noReady).waitUntilReady(5, TimeUnit.SECONDS);
      fail("should have thrown KubernetesClientException");
    } catch (KubernetesClientException e) {
      assertTrue(e.getCause() instanceof WaitForConditionWatcher.WatchException);
    }
  }

  @Test
  void testWaitOnConditionDeleted() throws InterruptedException {
    Pod ready = new PodBuilder().withNewMetadata()
      .withName("pod1")
      .withResourceVersion("1")
      .withNamespace("test").and().withNewStatus()
      .addNewCondition()
      .withType("Ready")
      .withStatus("True")
      .endCondition()
      .endStatus()
      .build();

    server.expect().get().withPath("/api/v1/namespaces/test/pods/pod1").andReturn(200, ready).once();

    server.expect().get().withPath("/api/v1/namespaces/test/pods?fieldSelector=metadata.name%3Dpod1&resourceVersion=1&watch=true").andUpgradeToWebSocket()
      .open()
      .waitFor(1000).andEmit(new WatchEvent(ready, "DELETED"))
      .done()
      .once();

    KubernetesClient client = server.getClient();
    Pod p = client.pods().withName("pod1").waitUntilCondition(Objects::isNull,8, TimeUnit.SECONDS);
    assertNull(p);
  }

  @Test
  public void testCreateAndWaitUntilReady() throws InterruptedException {
    Pod pod1 = new PodBuilder().withNewMetadata()
      .withName("pod1")
      .withResourceVersion("1")
      .withNamespace("test").and().build();


    Pod noReady = new PodBuilder(pod1).withNewStatus()
      .addNewCondition()
      .withType("Ready")
      .withStatus("False")
      .endCondition()
      .endStatus()
      .build();

    Pod ready = new PodBuilder(pod1).withNewStatus()
      .addNewCondition()
      .withType("Ready")
      .withStatus("True")
      .endCondition()
      .endStatus()
      .build();

    // once so that "waitUntilExists" successfully ends
    // and again so that "periodicWatchUntilReady" successfully begins
    server.expect().get().withPath("/api/v1/namespaces/test/pods/pod1").andReturn(200, noReady).times(2);
    server.expect().post().withPath("/api/v1/namespaces/test/pods").andReturn(201, noReady).once();

    server.expect().get().withPath("/api/v1/namespaces/test/pods?fieldSelector=metadata.name%3Dpod1&resourceVersion=1&watch=true").andUpgradeToWebSocket()
      .open()
      .waitFor(1000).andEmit(new WatchEvent(ready, "MODIFIED"))
      .done()
      .always();

    KubernetesClient client = server.getClient();
    Pod p = client.resource(noReady).createOrReplaceAnd().waitUntilReady(10, TimeUnit.SECONDS);
    Assert.assertTrue(Readiness.isPodReady(p));
  }

  @Test
  public void testFromServerGet() {
    Pod pod = new PodBuilder().withNewMetadata()
      .withName("pod1")
      .withNamespace("test")
      .withResourceVersion("1")
      .and()
      .build();

    server.expect().get().withPath("/api/v1/namespaces/test/pods/pod1").andReturn(200, pod).once();

    KubernetesClient client = server.getClient();
    HasMetadata response = client.resource(pod).fromServer().get();
    assertEquals(pod, response);
  }
}

