package it.valeriovaudi.helloservice;

import it.valeriovaudi.touppercase.proto.ToUpperCaseRequest;
import it.valeriovaudi.touppercase.proto.ToUpperCaseResponse;
import it.valeriovaudi.touppercase.proto.ToUpperCaseServiceGrpc;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class HelloServiceApplication {

    static void main(String[] args) {
        SpringApplication.run(HelloServiceApplication.class, args);
    }

    @Bean
    ToUpperCaseServiceGrpc.ToUpperCaseServiceBlockingStub stub(GrpcChannelFactory channels) {
        return ToUpperCaseServiceGrpc.newBlockingStub(channels.createChannel("local"));
    }
}

@Service
class HelloService {

    private final ToUpperCaseServiceGrpc.ToUpperCaseServiceBlockingStub stub;

    HelloService(ToUpperCaseServiceGrpc.ToUpperCaseServiceBlockingStub stub) {
        this.stub = stub;
    }

    public String sayHello(String name) {
        ToUpperCaseResponse toUpperCaseResponse = stub.toUpperCase(ToUpperCaseRequest.newBuilder().setMessage(name).build());
        return "Hello " + toUpperCaseResponse.getMessage();
    }
}

@RestController
class HelloEndPoint {

    private final HelloService helloService;

    HelloEndPoint(HelloService helloService) {
        this.helloService = helloService;
    }


    @GetMapping("/hello/{name}")
    private ResponseEntity helloTo(@PathVariable String name) {
        return ResponseEntity.ok().body(helloService.sayHello(name));
    }
}