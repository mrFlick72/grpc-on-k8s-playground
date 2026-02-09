package it.valeriovaudi.touppercaseservice;


import io.grpc.stub.StreamObserver;

import java.util.UUID;

import it.valeriovaudi.touppercase.proto.ToUpperCaseRequest;
import it.valeriovaudi.touppercase.proto.ToUpperCaseResponse;
import it.valeriovaudi.touppercase.proto.ToUpperCaseServiceGrpc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Service;

@SpringBootApplication
public class ToUpperCaseApplication {

    static void main(String[] args) {
        SpringApplication.run(ToUpperCaseApplication.class, args);
    }

}

@Service
class ToUpperCaseGrpcService extends ToUpperCaseServiceGrpc.ToUpperCaseServiceImplBase {

    private static final Logger logger = LoggerFactory.getLogger(ToUpperCaseGrpcService.class);

    private final String instanceId;

    ToUpperCaseGrpcService() {
        this.instanceId = UUID.randomUUID().toString();
        logger.debug("ToUpperCaseGrpcService instance id: {}", this.instanceId);
    }

    @Override
    public void toUpperCase(ToUpperCaseRequest request, StreamObserver<ToUpperCaseResponse> responseObserver) {

        ToUpperCaseResponse response = ToUpperCaseResponse.newBuilder()
                .setMessage("%s made upper case by instance: %s".formatted(request.getMessage().toUpperCase(), instanceId))
                .build();

        responseObserver.onNext(response);
        responseObserver.onCompleted();
    }
}