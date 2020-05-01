package com.cloudbees.jenkins.plugins.kubernetes_credentials_provider;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import com.cloudbees.jenkins.plugins.kubernetes_credentials_provider.convertors.UsernamePasswordCredentialsConvertor;
import com.cloudbees.plugins.credentials.common.UsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.ExtensionList;
import hudson.model.AdministrativeMonitor;
import hudson.model.ItemGroup;
import hudson.security.ACL;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.server.mock.KubernetesServer;
import jenkins.model.Jenkins;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

@RunWith(PowerMockRunner.class)
@PrepareForTest({ExtensionList.class})
@PowerMockIgnore({"okhttp3.*", "io.fabric8.*"})
public class KubernetesCredentialsProviderTest {

    public @Rule KubernetesServer server = new KubernetesServer();

    @Before
    public void setUp() {
        // mocked to validate add/remove of administrative errors
        ExtensionList<AdministrativeMonitor> monitors = ExtensionList.create((Jenkins) null, AdministrativeMonitor.class);
        // mocked to validate start watching for secrets
        ExtensionList<SecretToCredentialConverter> converters = ExtensionList.create((Jenkins) null, SecretToCredentialConverter.class);
        converters.addAll(Collections.singleton(new UsernamePasswordCredentialsConvertor()));
        PowerMockito.mockStatic(ExtensionList.class);
        PowerMockito.when(ExtensionList.lookup(AdministrativeMonitor.class)).thenReturn(monitors);
        PowerMockito.when(ExtensionList.lookup(SecretToCredentialConverter.class)).thenReturn(converters);
    }

    private void defaultMockKubernetesResponses() {
        mockKubernetesResponses("jenkins.io%2Fcredentials-type");
    }

    private void mockKubernetesResponses(String labelSelector) {
        server.expect().withPath("/api/v1/namespaces/test/secrets?labelSelector=" + labelSelector)
                .andReturn(200, new SecretListBuilder()
                        .withNewMetadata()
                        .withResourceVersion("1")
                        .endMetadata()
                        .build()).always();
        server.expect().withPath("/api/v1/namespaces/test/secrets?labelSelector=" + labelSelector + "&resourceVersion=1&watch=true")
                .andReturn(200, null).always();
    }

    @Test
    public void startWatchingForSecrets_Scoped() throws IOException {
        Map<String, String> s4Labels = new HashMap<>();
        s4Labels.put("jenkins.io/credentials-scope", "/my-item-group");
        Secret s4 = createSecret("s4", s4Labels);

        server.expect().withPath("/api/v1/namespaces/test/secrets?labelSelector=jenkins.io%2Fcredentials-type")
                .andReturn(200, new SecretListBuilder()
                        .withNewMetadata()
                        .withResourceVersion("1")
                        .endMetadata()
                        .addToItems(s4)
                        .build())
                .once();

        KubernetesCredentialProvider provider = new MockedKubernetesCredentialProvider();
        provider.startWatchingForSecrets();

        ItemGroup group = mock(ItemGroup.class);
        when(group.getUrl()).thenReturn("my-item-group/");

        List<UsernamePasswordCredentials> credentials = provider.getCredentials(UsernamePasswordCredentials.class, group, ACL.SYSTEM);
        assertEquals("credentials", 1, credentials.size());
        assertTrue("secret s4 exists", credentials.stream().anyMatch(c -> "s4".equals(((UsernamePasswordCredentialsImpl) c).getId())));
    }

    @Test
    public void startWatchingForSecrets() {
        Secret s1 = createSecret("s1");
        Secret s2 = createSecret("s2");
        Secret s3 = createSecret("s3");

        // returns s1 and s3, the credentials map should be reset to this list
        server.expect().withPath("/api/v1/namespaces/test/secrets?labelSelector=jenkins.io%2Fcredentials-type")
                .andReturn(200, new SecretListBuilder()
                        .withNewMetadata()
                        .withResourceVersion("1")
                        .endMetadata()
                        .addToItems(s1, s3)
                        .build())
                .once();

        // expect the s2 will get dropped when the credentials map is reset to the full list
        server.expect().withPath("/api/v1/namespaces/test/secrets?labelSelector=jenkins.io%2Fcredentials-type&watch=true")
                .andReturnChunked(200, new WatchEvent(s1, "ADDED"), new WatchEvent(s2, "ADDED"))
                .once();
        server.expect().withPath("/api/v1/namespaces/test/secrets?labelSelector=jenkins.io%2Fcredentials-type&watch=true")
                .andReturn(200, null)
                .always();

        KubernetesCredentialProvider provider = new MockedKubernetesCredentialProvider();
        provider.startWatchingForSecrets();

        List<UsernamePasswordCredentials> credentials = provider.getCredentials(UsernamePasswordCredentials.class, (ItemGroup) null, ACL.SYSTEM);
        assertEquals("credentials", 2, credentials.size());
        assertTrue("secret s1 exists", credentials.stream().anyMatch(c -> "s1".equals(((UsernamePasswordCredentialsImpl) c).getId())));
        assertTrue("secret s3 exists", credentials.stream().anyMatch(c -> "s3".equals(((UsernamePasswordCredentialsImpl) c).getId())));
    }

    private Secret createSecret(String name, Map<String, String> labels) {
        Map<String, String> labelsCopy = new HashMap<>(labels);
        labelsCopy.put("jenkins.io/credentials-type", "usernamePassword");

        return new SecretBuilder()
                .withNewMetadata()
                .withNamespace("test")
                .withName(name)
                .addToLabels(labelsCopy)
                .endMetadata()
                .addToData("username", "bXlVc2VybmFtZQ==")
                .addToData("password", "UGEkJHdvcmQ=")
                .build();
    }

    private Secret createSecret(String name) {
        return createSecret(name, Collections.emptyMap());
    }

    @Test
    public void startWatchingForSecretsKubernetesClientException() throws IOException {
        KubernetesCredentialProvider provider = new MockedKubernetesCredentialProvider();
        provider.startWatchingForSecrets();
        assertEquals("expect administrative error", 1, getInitAdministrativeMonitorCount());

        // enable default responses
        defaultMockKubernetesResponses();
        // restart with success should clear errors
        provider.startWatchingForSecrets();
        assertEquals("expect administrative error to be cleared", 0, getInitAdministrativeMonitorCount());
    }

    private long getInitAdministrativeMonitorCount() {
        return AdministrativeMonitor.all().stream()
                .filter(am -> am.id.equals(MockedKubernetesCredentialProvider.class.getName() + ".initialize"))
                .count();
    }

    @Test
    public void restartWatchOnCloseException() throws Exception {
        defaultMockKubernetesResponses();
        KubernetesCredentialProvider provider = new MockedKubernetesCredentialProvider();
        provider.startWatchingForSecrets();
        provider.onClose(new KubernetesClientException("test exception"));
        // expect 2 requests to list
        assertRequestCount("/api/v1/namespaces/test/secrets?labelSelector=jenkins.io%2Fcredentials-type", 2);
    }

    @Test
    public void noRestartWatchOnCloseNormal() throws Exception {
        defaultMockKubernetesResponses();
        KubernetesCredentialProvider provider = new MockedKubernetesCredentialProvider();
        provider.startWatchingForSecrets();

        provider.onClose(null);
        // expect 1 requests to list
        assertRequestCount("/api/v1/namespaces/test/secrets?labelSelector=jenkins.io%2Fcredentials-type", 1);
    }

    @Test
    public void startWatchingWithCustomLabelSelectors() throws InterruptedException {
        try {
            System.setProperty(KubernetesCredentialProvider.LABEL_SELECTOR, "env in (iat uat)");
            mockKubernetesResponses("jenkins.io%2Fcredentials-type%2Cenv%20in%20%28iat%20uat%29");
            KubernetesCredentialProvider provider = new MockedKubernetesCredentialProvider();
            provider.startWatchingForSecrets();

            assertRequestCount("/api/v1/namespaces/test/secrets?labelSelector=jenkins.io%2Fcredentials-type%2Cenv%20in%20%28iat%20uat%29", 1);
            assertRequestCountAtLeast("/api/v1/namespaces/test/secrets?labelSelector=jenkins.io%2Fcredentials-type%2Cenv%20in%20%28iat%20uat%29&resourceVersion=1&watch=true", 1);
        } finally {
            System.clearProperty(KubernetesCredentialProvider.LABEL_SELECTOR);
        }
    }

    @Test
    public void startWatchingWithCustomLabelException() throws IOException {
        try {
            System.setProperty(KubernetesCredentialProvider.LABEL_SELECTOR, "partition  in");
            KubernetesCredentialProvider provider = new MockedKubernetesCredentialProvider();
            provider.startWatchingForSecrets();
            assertEquals("expect administrative error", 1, getLabelSelectorAdministrativeMonitorCount());

            System.setProperty(KubernetesCredentialProvider.LABEL_SELECTOR, "env in (iat uat)");
            // enable default responses
            mockKubernetesResponses("jenkins.io%2Fcredentials-type%2Cenv%20in%20%28iat%20uat%29");
            // restart with success should clear errors
            provider.startWatchingForSecrets();
            assertEquals("expect administrative error to be cleared", 0, getLabelSelectorAdministrativeMonitorCount());
        } finally {
            System.clearProperty(KubernetesCredentialProvider.LABEL_SELECTOR);
        }
    }

    private long getLabelSelectorAdministrativeMonitorCount() {
        return AdministrativeMonitor.all().stream()
                .filter(am -> am.id.equals(MockedKubernetesCredentialProvider.class.getName() + ".labelSelector"))
                .count();
    }

    private void assertRequestCount(String path, long count) throws InterruptedException {
        assertEquals(path, count, requestCount(path));
    }

    private void assertRequestCountAtLeast(String path, long count) throws InterruptedException {
        assertTrue(path + " >= " + count, requestCount(path) >= count);
    }

    private long requestCount(String path) throws InterruptedException {
        return getRequests().stream()
                .filter(r -> r.getPath().equals(path))
                .count();
    }

    private List<RecordedRequest> getRequests() throws InterruptedException {
        int count = server.getMockServer().getRequestCount();
        List<RecordedRequest> requests = new LinkedList<>();
        while (count-- > 0) {
            requests.add(server.getMockServer().takeRequest());
        }
        return requests;
    }

    private class MockedKubernetesCredentialProvider extends KubernetesCredentialProvider {
        @Override
        KubernetesClient getKubernetesClient() {
            return server.getClient();
        }
    }
}
