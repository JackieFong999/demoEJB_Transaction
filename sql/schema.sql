-- ============================================================
-- demoEJB_Transaction  |  schema for D:\APPS_EJB_20261119\demoEJB_Transaction
-- Target : MySQL 8.4 (container mysqljavadev), schema demoEJB_DB, port 3308
-- Engine : InnoDB  <-- REQUIRED, only InnoDB rolls back
-- Re-runnable: drops and recreates both tables.
-- ============================================================

DROP TABLE IF EXISTS tblAccount;
DROP TABLE IF EXISTS tblCustomer;

-- 1) Customer: who they are, how to reach them.
CREATE TABLE tblCustomer (
    Cust_Name  VARCHAR(64) NOT NULL,
    Tel        VARCHAR(32) NOT NULL,
    PRIMARY KEY (Cust_Name)          -- duplicate name -> error -> transaction rollback demo
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- 2) Account: one row per money movement for that customer.
--    Deposit_Withdraw = 'D' (deposit) or 'W' (withdraw)
CREATE TABLE tblAccount (
    Cust_Name        VARCHAR(64)   NOT NULL,
    Deposit_Withdraw CHAR(1)       NOT NULL,
    Amount           DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    KEY idx_account_customer (Cust_Name),
    CONSTRAINT fk_account_customer FOREIGN KEY (Cust_Name)
        REFERENCES tblCustomer (Cust_Name),
    CONSTRAINT chk_deposit_withdraw CHECK (Deposit_Withdraw IN ('D','W'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
