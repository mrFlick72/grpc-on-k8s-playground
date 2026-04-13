package it.valeriovaudi.helloservice;

import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.kubernetes.client.openapi.models.V1Endpoint;
import io.kubernetes.client.openapi.models.V1EndpointConditions;
import io.kubernetes.client.openapi.models.V1EndpointSlice;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KubernetesDnsNameResolverProviderTest {

    private final KubernetesDnsNameResolverProvider provider = new KubernetesDnsNameResolverProvider();

    @Nested
    class ProviderTests {

        @Test
        void getDefaultScheme_returnsK8s() {
            assertThat(provider.getDefaultScheme()).isEqualTo("k8s");
        }

        @Test
        void isAvailable_returnsTrue() {
            assertThat(provider.isAvailable()).isTrue();
        }

        @Test
        void priority_returns6() {
            assertThat(provider.priority()).isEqualTo(6);
        }

        @Test
        void newNameResolver_withK8sScheme_returnsNonNull() {
            URI uri = URI.create("k8s:///my-service:9090");
            NameResolver.Args args = mock(NameResolver.Args.class);
            when(args.getDefaultPort()).thenReturn(9090);

            NameResolver resolver = provider.newNameResolver(uri, args);

            assertThat(resolver).isNotNull();
            resolver.shutdown();
        }

        @Test
        void newNameResolver_withDnsScheme_returnsNull() {
            URI uri = URI.create("dns:///my-service:9090");
            NameResolver.Args args = mock(NameResolver.Args.class);

            NameResolver resolver = provider.newNameResolver(uri, args);

            assertThat(resolver).isNull();
        }

        @Test
        void newNameResolver_returnsCorrectAuthority() {
            URI uri = URI.create("k8s:///my-service:8080");
            NameResolver.Args args = mock(NameResolver.Args.class);
            when(args.getDefaultPort()).thenReturn(9090);

            NameResolver resolver = provider.newNameResolver(uri, args);

            assertThat(resolver.getServiceAuthority()).isEqualTo("my-service:8080");
            resolver.shutdown();
        }
    }

    @Nested
    class EndpointTargetParsingTests {

        @Test
        void from_withServiceAndPort() {
            URI uri = URI.create("k8s:///to-upper-case-service:9090");
            var target = KubernetesDnsNameResolverProvider.EndpointTarget.from(uri, 0);

            assertThat(target.serviceName()).isEqualTo("to-upper-case-service");
            assertThat(target.port()).isEqualTo(9090);
            assertThat(target.authority()).isEqualTo("to-upper-case-service:9090");
        }

        @Test
        void from_withServiceNamespaceAndPort() {
            URI uri = URI.create("k8s:///my-service.my-namespace:8080");
            var target = KubernetesDnsNameResolverProvider.EndpointTarget.from(uri, 0);

            assertThat(target.serviceName()).isEqualTo("my-service");
            assertThat(target.namespace()).isEqualTo("my-namespace");
            assertThat(target.port()).isEqualTo(8080);
        }

        @Test
        void from_withoutPort_usesDefaultPort() {
            URI uri = URI.create("k8s:///my-service.my-namespace");
            var target = KubernetesDnsNameResolverProvider.EndpointTarget.from(uri, 7070);

            assertThat(target.serviceName()).isEqualTo("my-service");
            assertThat(target.namespace()).isEqualTo("my-namespace");
            assertThat(target.port()).isEqualTo(7070);
        }

        @Test
        void from_withoutPortAndNoDefault_fallsBackTo9090() {
            URI uri = URI.create("k8s:///my-service.my-namespace");
            var target = KubernetesDnsNameResolverProvider.EndpointTarget.from(uri, 0);

            assertThat(target.port()).isEqualTo(9090);
        }

        @Test
        void from_withEmptyPath_throwsException() {
            URI uri = URI.create("k8s:///");
            assertThatThrownBy(() -> KubernetesDnsNameResolverProvider.EndpointTarget.from(uri, 0))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("k8s URI must have a path");
        }

        @Test
        void from_withoutNamespace_defaultsNamespace() {
            URI uri = URI.create("k8s:///my-service:9090");
            var target = KubernetesDnsNameResolverProvider.EndpointTarget.from(uri, 0);

            assertThat(target.serviceName()).isEqualTo("my-service");
            // Outside K8s, namespace falls back to "default"
            assertThat(target.namespace()).isEqualTo("default");
        }
    }

    @Nested
    class ResolveAddressesTests {

        @Test
        void withReadyEndpoints_returnsAllAddresses() {
            V1EndpointSlice slice = new V1EndpointSlice()
                    .endpoints(List.of(
                            readyEndpoint("10.0.0.1"),
                            readyEndpoint("10.0.0.2"),
                            readyEndpoint("10.0.0.3")
                    ));

            List<EquivalentAddressGroup> result =
                    KubernetesDnsNameResolverProvider.resolveAddresses(List.of(slice), 9090);

            assertThat(result).hasSize(3);
            assertThat(extractIps(result)).containsExactly("10.0.0.1", "10.0.0.2", "10.0.0.3");
        }

        @Test
        void filtersNotReadyEndpoints() {
            V1EndpointSlice slice = new V1EndpointSlice()
                    .endpoints(List.of(
                            readyEndpoint("10.0.0.1"),
                            notReadyEndpoint("10.0.0.2"),
                            readyEndpoint("10.0.0.3")
                    ));

            List<EquivalentAddressGroup> result =
                    KubernetesDnsNameResolverProvider.resolveAddresses(List.of(slice), 9090);

            assertThat(result).hasSize(2);
            assertThat(extractIps(result)).containsExactly("10.0.0.1", "10.0.0.3");
        }

        @Test
        void withNoEndpoints_returnsEmpty() {
            V1EndpointSlice slice = new V1EndpointSlice()
                    .endpoints(List.of());

            List<EquivalentAddressGroup> result =
                    KubernetesDnsNameResolverProvider.resolveAddresses(List.of(slice), 9090);

            assertThat(result).isEmpty();
        }

        @Test
        void sortsAddressesDeterministically() {
            V1EndpointSlice slice = new V1EndpointSlice()
                    .endpoints(List.of(
                            readyEndpoint("10.0.0.3"),
                            readyEndpoint("10.0.0.1"),
                            readyEndpoint("10.0.0.2")
                    ));

            List<EquivalentAddressGroup> result =
                    KubernetesDnsNameResolverProvider.resolveAddresses(List.of(slice), 9090);

            assertThat(extractIps(result)).containsExactly("10.0.0.1", "10.0.0.2", "10.0.0.3");
        }

        @Test
        void usesCorrectPort() {
            V1EndpointSlice slice = new V1EndpointSlice()
                    .endpoints(List.of(readyEndpoint("10.0.0.1")));

            List<EquivalentAddressGroup> result =
                    KubernetesDnsNameResolverProvider.resolveAddresses(List.of(slice), 8080);

            InetSocketAddress addr = (InetSocketAddress) result.getFirst().getAddresses().getFirst();
            assertThat(addr.getPort()).isEqualTo(8080);
        }

        @Test
        void acrossMultipleSlices() {
            V1EndpointSlice slice1 = new V1EndpointSlice()
                    .endpoints(List.of(readyEndpoint("10.0.0.1"), readyEndpoint("10.0.0.2")));
            V1EndpointSlice slice2 = new V1EndpointSlice()
                    .endpoints(List.of(readyEndpoint("10.0.0.3")));

            List<EquivalentAddressGroup> result =
                    KubernetesDnsNameResolverProvider.resolveAddresses(List.of(slice1, slice2), 9090);

            assertThat(result).hasSize(3);
            assertThat(extractIps(result)).containsExactly("10.0.0.1", "10.0.0.2", "10.0.0.3");
        }

        @Test
        void endpointWithNullConditions_isTreatedAsReady() {
            V1Endpoint endpoint = new V1Endpoint().addresses(List.of("10.0.0.1"));
            // conditions is null — no readiness info means assume ready
            V1EndpointSlice slice = new V1EndpointSlice().endpoints(List.of(endpoint));

            List<EquivalentAddressGroup> result =
                    KubernetesDnsNameResolverProvider.resolveAddresses(List.of(slice), 9090);

            assertThat(result).hasSize(1);
            assertThat(extractIps(result)).containsExactly("10.0.0.1");
        }

        @Test
        void allEndpointsNotReady_returnsEmpty() {
            V1EndpointSlice slice = new V1EndpointSlice()
                    .endpoints(List.of(
                            notReadyEndpoint("10.0.0.1"),
                            notReadyEndpoint("10.0.0.2")
                    ));

            List<EquivalentAddressGroup> result =
                    KubernetesDnsNameResolverProvider.resolveAddresses(List.of(slice), 9090);

            assertThat(result).isEmpty();
        }

        private V1Endpoint readyEndpoint(String ip) {
            return new V1Endpoint()
                    .addresses(List.of(ip))
                    .conditions(new V1EndpointConditions().ready(true));
        }

        private V1Endpoint notReadyEndpoint(String ip) {
            return new V1Endpoint()
                    .addresses(List.of(ip))
                    .conditions(new V1EndpointConditions().ready(false));
        }

        private List<String> extractIps(List<EquivalentAddressGroup> groups) {
            return groups.stream()
                    .map(eag -> (InetSocketAddress) eag.getAddresses().getFirst())
                    .map(InetSocketAddress::getHostString)
                    .toList();
        }
    }
}
