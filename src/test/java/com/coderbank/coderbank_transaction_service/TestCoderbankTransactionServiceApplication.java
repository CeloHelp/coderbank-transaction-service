package com.coderbank.coderbank_transaction_service;

import org.springframework.boot.SpringApplication;

public class TestCoderbankTransactionServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(CoderbankTransactionServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
