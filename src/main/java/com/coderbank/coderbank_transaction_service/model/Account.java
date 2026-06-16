package com.coderbank.coderbank_transaction_service.model;


import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Getter
   private UUID id;


    @Getter
   private String customerId;;

    @Getter
    @Setter
   private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Getter
    @Setter
    private CurrencyType currency;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    @Getter
    private LocalDateTime createdAt;
}
