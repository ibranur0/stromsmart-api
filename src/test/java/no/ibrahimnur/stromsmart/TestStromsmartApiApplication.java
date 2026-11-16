package no.ibrahimnur.stromsmart;

import org.springframework.boot.SpringApplication;

public class TestStromsmartApiApplication {

	public static void main(String[] args) {
		SpringApplication.from(StromsmartApiApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
