package it.valeriovaudi.helloservice;

import io.grpc.Attributes;
import io.grpc.EquivalentAddressGroup;
import io.grpc.NameResolver;
import io.grpc.NameResolverProvider;
import io.grpc.Status;
import io.kubernetes.client.informer.ResourceEventHandler;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.openapi.ApiClient;
import io.kubernetes.client.openapi.apis.DiscoveryV1Api;
import io.kubernetes.client.openapi.models.V1EndpointSlice;
import io.kubernetes.client.openapi.models.V1EndpointSliceList;
import io.kubernetes.client.util.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class KubernetesDnsNameResolverProvider extends NameResolverProvider {

    private static final String SCHEME = "k8s";
    private static final Logger logger = LoggerFactory.getLogger(KubernetesDnsNameResolverProvider.class);

    @Override
    public NameResolver newNameResolver(URI targetUri, NameResolver.Args args) {
        if (!SCHEME.equals(targetUri.getScheme())) {
            return null;
        }
        EndpointTarget target = EndpointTarget.from(targetUri, args.getDefaultPort());
        logger.info("Creating Kubernetes EndpointSlice NameResolver for service={} namespace={} port={}",
                target.serviceName(), target.namespace(), target.port());
        return new KubernetesEndpointSliceNameResolver(target, args);
    }

    @Override
    public String getDefaultScheme() {
        return SCHEME;
    }

    @Override
    protected boolean isAvailable() {
        return true;
    }

    @Override
    protected int priority() {
        return 6;
    }

    @Override
    public Collection<Class<? extends SocketAddress>> getProducedSocketAddressTypes() {
        return Collections.singleton(InetSocketAddress.class);
    }

    private record EndpointTarget(String authority, String serviceName, String namespace, int port) {

        private static final Path NAMESPACE_PATH = Path.of("/var/run/secrets/kubernetes.io/serviceaccount/namespace");
        private static volatile String cachedNamespace;

        static EndpointTarget from(URI uri, int defaultPort) {
            String path = uri.getPath();
            if (path != null && path.startsWith("/")) {
                path = path.substring(1);
            }
            if (path == null || path.isEmpty()) {
                throw new IllegalArgumentException("k8s URI must have a path: " + uri);
            }

            String hostPart;
            int port;
            int colonIdx = path.lastIndexOf(':');
            if (colonIdx > 0) {
                hostPart = path.substring(0, colonIdx);
                port = Integer.parseInt(path.substring(colonIdx + 1));
            } else {
                hostPart = path;
                port = defaultPort > 0 ? defaultPort : 9090;
            }

            String serviceName;
            String namespace;
            int dotIdx = hostPart.indexOf('.');
            if (dotIdx > 0) {
                serviceName = hostPart.substring(0, dotIdx);
                namespace = hostPart.substring(dotIdx + 1);
            } else {
                serviceName = hostPart;
                namespace = detectNamespace();
            }

            String authority = hostPart + ":" + port;
            return new EndpointTarget(authority, serviceName, namespace, port);
        }

        private static String detectNamespace() {
            if (cachedNamespace != null) {
                return cachedNamespace;
            }
            try {
                if (Files.exists(NAMESPACE_PATH)) {
                    cachedNamespace = Files.readString(NAMESPACE_PATH).trim();
                    return cachedNamespace;
                }
            } catch (IOException e) {
                logger.warn("Failed to read namespace from {}", NAMESPACE_PATH, e);
            }
            cachedNamespace = "default";
            return cachedNamespace;
        }
    }

    private static final class KubernetesEndpointSliceNameResolver extends NameResolver {

        private static final Logger logger = LoggerFactory.getLogger(KubernetesEndpointSliceNameResolver.class);
        private static final long DEBOUNCE_MILLIS = 100;
        private static final long RESYNC_PERIOD_MILLIS = 60_000;

        private final EndpointTarget target;
        private final NameResolver.Args args;
        private volatile Listener2 listener;
        private volatile SharedInformerFactory informerFactory;
        private volatile SharedIndexInformer<V1EndpointSlice> informer;
        private volatile List<EquivalentAddressGroup> lastPushedAddresses = List.of();
        private final ScheduledExecutorService debounceExecutor;
        private volatile ScheduledFuture<?> pendingDebounce;

        KubernetesEndpointSliceNameResolver(EndpointTarget target, NameResolver.Args args) {
            this.target = target;
            this.args = args;
            this.debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "k8s-resolver-debounce-" + target.serviceName());
                t.setDaemon(true);
                return t;
            });
        }

        @Override
        public String getServiceAuthority() {
            return target.authority();
        }

        @Override
        public void start(Listener2 listener) {
            this.listener = listener;

            ApiClient apiClient;
            try {
                apiClient = Config.fromCluster();
                apiClient.setReadTimeout(0);
            } catch (IOException e) {
                logger.error("Failed to create in-cluster Kubernetes API client", e);
                listener.onError(Status.UNAVAILABLE
                        .withDescription("Cannot create Kubernetes API client — not running in-cluster?")
                        .withCause(e));
                return;
            }

            informerFactory = new SharedInformerFactory(apiClient);
            DiscoveryV1Api discoveryApi = new DiscoveryV1Api(apiClient);

            String labelSelector = "kubernetes.io/service-name=" + target.serviceName();
            logger.info("Starting EndpointSlice watch for namespace={} selector={}", target.namespace(), labelSelector);

            informer = informerFactory.sharedIndexInformerFor(
                    callGeneratorParams -> discoveryApi.listNamespacedEndpointSlice(target.namespace())
                            .labelSelector(labelSelector)
                            .resourceVersion(callGeneratorParams.resourceVersion)
                            .timeoutSeconds(callGeneratorParams.timeoutSeconds)
                            .watch(callGeneratorParams.watch)
                            .buildCall(null),
                    V1EndpointSlice.class,
                    V1EndpointSliceList.class,
                    RESYNC_PERIOD_MILLIS
            );

            informer.addEventHandler(new ResourceEventHandler<>() {
                @Override
                public void onAdd(V1EndpointSlice slice) {
                    logger.debug("EndpointSlice ADDED: {}", sliceName(slice));
                    scheduleResolve();
                }

                @Override
                public void onUpdate(V1EndpointSlice oldSlice, V1EndpointSlice newSlice) {
                    logger.debug("EndpointSlice UPDATED: {}", sliceName(newSlice));
                    scheduleResolve();
                }

                @Override
                public void onDelete(V1EndpointSlice slice, boolean deletedFinalStateUnknown) {
                    logger.debug("EndpointSlice DELETED: {}", sliceName(slice));
                    scheduleResolve();
                }

                private String sliceName(V1EndpointSlice slice) {
                    return slice.getMetadata() != null ? slice.getMetadata().getName() : "unknown";
                }
            });

            informerFactory.startAllRegisteredInformers();
        }

        @Override
        public void refresh() {
            resolveNow();
        }

        @Override
        public void shutdown() {
            logger.info("Shutting down Kubernetes NameResolver for {}", target.serviceName());
            if (informerFactory != null) {
                informerFactory.stopAllRegisteredInformers();
            }
            debounceExecutor.shutdownNow();
        }

        private void scheduleResolve() {
            ScheduledFuture<?> existing = pendingDebounce;
            if (existing != null && !existing.isDone()) {
                existing.cancel(false);
            }
            pendingDebounce = debounceExecutor.schedule(this::resolveNow, DEBOUNCE_MILLIS, TimeUnit.MILLISECONDS);
        }

        private void resolveNow() {
            if (informer == null || !informer.hasSynced()) {
                logger.debug("Informer not yet synced, skipping resolve");
                return;
            }

            List<V1EndpointSlice> slices = informer.getIndexer().list();
            List<EquivalentAddressGroup> newAddresses = new ArrayList<>();

            for (V1EndpointSlice slice : slices) {
                for (var endpoint : slice.getEndpoints()) {
                    if (endpoint.getConditions() != null
                            && !Boolean.TRUE.equals(endpoint.getConditions().getReady())) {
                        continue;
                    }
                    for (String address : endpoint.getAddresses()) {
                        newAddresses.add(
                                new EquivalentAddressGroup(new InetSocketAddress(address, target.port()))
                        );
                    }
                }
            }

            newAddresses.sort(Comparator.comparing(
                    eag -> eag.getAddresses().getFirst().toString()
            ));

            if (newAddresses.equals(lastPushedAddresses)) {
                return;
            }

            lastPushedAddresses = List.copyOf(newAddresses);
            int addressCount = newAddresses.size();
            logger.info("Resolved {} endpoints for {}", addressCount, target.serviceName());

            if (newAddresses.isEmpty()) {
                args.getSynchronizationContext().execute(() ->
                        listener.onError(Status.UNAVAILABLE
                                .withDescription("No ready endpoints for service " + target.serviceName()))
                );
            } else {
                List<EquivalentAddressGroup> finalAddresses = newAddresses;
                args.getSynchronizationContext().execute(() ->
                        listener.onResult(ResolutionResult.newBuilder()
                                .setAddresses(finalAddresses)
                                .setAttributes(Attributes.EMPTY)
                                .build())
                );
            }
        }
    }
}
