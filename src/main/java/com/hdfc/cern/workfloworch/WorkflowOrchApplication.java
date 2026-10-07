package com.hdfc.cern.workfloworch;

import java.util.TimeZone;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class WorkflowOrchApplication {

	public static void main(String[] args) {
		// pgjdbc sends the JVM's default zone ID as the connection's "TimeZone" startup parameter; on
		// this host that ID can be the legacy alias "Asia/Calcutta", which Postgres 16 rejects. Force
		// a canonical zone before any datasource is touched (same fix as temporal-workflow-service).
		TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
		SpringApplication.run(WorkflowOrchApplication.class, args);
	}

}
