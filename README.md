# demoEJB_Transaction — EJB + JTA + MySQL, no Spring anywhere

A minimal Jakarta EE app whose only purpose is to make a **container-managed transaction**
visible: one Submit writes to **two MySQL tables** through one `@Stateless` EJB, and both rows
commit or roll back together.

```
browser (index.html: 2 fields + Submit)
   │  POST /register   (form-urlencoded)
   ▼
RegisterServlet            @WebServlet  — validation + HTML only, no SQL
   │  @EJB → proxy
   ▼
CustomerAccountBean        @Stateless   — 2 × INSERT, zero transaction code
   │  @Resource DataSource → JTA-enlisted connection
   ▼
MySQL 8.4 (container mysqljavadev, host 127.0.0.1:3308, schema demoEJB_DB)
   tblCustomer          tblAccount
```

---

## 1. Files

| File | Role |
|---|---|
| `src/main/java/com/example/transaction/CustomerAccountBean.java` | **The EJB.** Both INSERTs + the transaction demo hooks. |
| `src/main/java/com/example/transaction/RegisterServlet.java` | Web entry point, `@EJB` injection, renders the result page. |
| `src/main/webapp/index.html` | Page with exactly two fields and **two buttons**: `Submit` (form POST) and `Submit-JS` (`fetch`, no reload). |
| `src/main/webapp/WEB-INF/beans.xml` | Marks the WAR a bean archive (so `@EJB` in the servlet is processed). |
| `sql/schema.sql` | Re-runnable DDL for both tables. |
| `scripts/deploy.bat` | Build + hot-deploy + readiness check. |
| `scripts/verify.sh` | **Canonical ad-hoc verifier**: rebuild → redeploy → 24 assertions (both buttons, HTML-parse contract, rollback, DB state). Run `bash scripts/verify.sh`. |

## 2. Schema

```sql
CREATE TABLE tblCustomer (
    Cust_Name  VARCHAR(64) NOT NULL,
    Tel        VARCHAR(32) NOT NULL,
    PRIMARY KEY (Cust_Name)              -- duplicate name => error => rollback demo
) ENGINE=InnoDB;

CREATE TABLE tblAccount (
    Cust_Name        VARCHAR(64)   NOT NULL,
    Deposit_Withdraw CHAR(1)       NOT NULL,   -- 'D' deposit / 'W' withdraw
    Amount           DECIMAL(12,2) NOT NULL DEFAULT 0.00,
    KEY idx_account_customer (Cust_Name),
    CONSTRAINT fk_account_customer FOREIGN KEY (Cust_Name) REFERENCES tblCustomer (Cust_Name),
    CONSTRAINT chk_deposit_withdraw CHECK (Deposit_Withdraw IN ('D','W'))
) ENGINE=InnoDB;
```

`ENGINE=InnoDB` is not optional — MyISAM would silently **not** roll back and destroy the lesson.
Apply with:

```bat
"D:\Program Files\MySQL\MySQL Workbench 8.0 CE\mysql.exe" -h 127.0.0.1 -P 3308 ^
   -u ejb_demo_user -p demoEJB_DB < sql\schema.sql
```

## 3. Infrastructure (already installed on this machine)

**a) JDBC driver module** — `D:\Download\jboss-eap-8.0.0\jboss-eap-8.0\modules\com\mysql\main\`:

```
module.xml
mysql-connector-j-8.3.0.jar        (copied from ~/.m2/repository/com/mysql/mysql-connector-j/8.3.0)
```

```xml
<module xmlns="urn:jboss:module:1.9" name="com.mysql">
    <resources><resource-root path="mysql-connector-j-8.3.0.jar"/></resources>
    <dependencies>
        <module name="java.sql"/>
        <module name="java.transaction.xa"/>
        <module name="java.naming"/>
        <module name="java.security.jgss"/>
        <module name="java.security.sasl"/>   <!-- REQUIRED by Connector/J 8.x, else ClassNotFoundException -->
        <module name="java.management"/>
    </dependencies>
</module>
```

**b) Datasource** — `standalone.xml`, `datasources` subsystem:

```xml
<driver name="mysql" module="com.mysql">
    <driver-class>com.mysql.cj.jdbc.Driver</driver-class>
</driver>

<datasource jndi-name="java:jboss/datasources/DemoEJBMySQLDS" pool-name="DemoEJBMySQLDS" enabled="true">
    <connection-url>jdbc:mysql://127.0.0.1:3308/demoEJB_DB?useSSL=false&amp;allowPublicKeyRetrieval=true&amp;serverTimezone=Asia/Hong_Kong&amp;characterEncoding=UTF-8</connection-url>
    <driver>mysql</driver>
    <security><user-name>ejb_demo_user</user-name><password>OceanPark88</password></security>
    <validation>
        <valid-connection-checker class-name="org.jboss.jca.adapters.jdbc.extensions.mysql.MySQLValidConnectionChecker"/>
        <check-valid-connection-sql>SELECT 1</check-valid-connection-sql>
        <validate-on-match>true</validate-on-match>
    </validation>
</datasource>
```

Expected boot lines:

```
WFLYJCA0018: Started Driver service with driver-name = mysql
WFLYJCA0001: Bound data source [java:jboss/datasources/DemoEJBMySQLDS]
```

> ⚠ The URL uses **127.0.0.1:3308** (host port). `mysqljavadev:3306` only resolves *inside* Docker.

## 4. Run

```bat
:: 1. server (this project piggybacks on the other demo's launcher)
D:\APPS_EJB_20261119\demoEJB_Stateless\scripts\start-server.bat

:: 2. build + deploy
D:\APPS_EJB_20261119\demoEJB_Transaction\scripts\deploy.bat
```

Then open **http://localhost:8080/demoEJB-Transaction/** and submit a name + tel.

To prove the whole thing without clicking:

```bat
bash scripts\verify.sh      :: build + deploy + 24 assertions against MySQL
```

## 5. Proof of the transaction (measured)

| # | Action | Result | DB |
|---|---|---|---|
| A | Submit `Alice / 12345678` | `COMMITTED — 1 row in tblCustomer + 1 row in tblAccount` | 1 + 1 rows |
| B | Submit `Alice` again | `ROLLED BACK — SQLIntegrityConstraintViolationException: Duplicate entry 'Alice'` | unchanged (tel still `12345678`) |
| C | Submit `Bob` with `forceFail=true` — throws **after both INSERTs succeeded** | `ROLLED BACK — IllegalStateException: FORCED FAILURE after both inserts` | `tblCustomer` has **0** Bob rows, `tblAccount` count unchanged → both inserts undone |

Reproduce B and C from the command line:

```bat
curl -X POST -d "custName=Alice&tel=99999999" http://localhost:8080/demoEJB-Transaction/register
curl -X POST -d "custName=Bob&tel=88888888&forceFail=true" http://localhost:8080/demoEJB-Transaction/register
```

The bean also logs the container's transaction state **before your first statement**:

```
[CustomerAccountBean] -> tx status at entry = 0 (0 = ACTIVE, already started by the container)
[CustomerAccountBean] tblCustomer rows=1, tblAccount rows=1 (nothing is committed yet ...)
```

`0` is `jakarta.transaction.Status.STATUS_ACTIVE`.

## 6. Two buttons, one servlet (client-side only)

`index.html` has the same two fields but **two submit paths**, both hitting the *unchanged* servlet:

| Button | Request | Response | Page |
|---|---|---|---|
| **Submit** (blue) | `POST /register` (form POST) | `text/html` — whole page | navigates to `/register` |
| **Submit-JS** (green) | `fetch('register')` | `text/html` — **parsed in the browser** | stays on `/`, panel updated |

The servlet knows nothing about the second button. `index.html` does all the work:

```js
const res  = await fetch('register', {method:'POST', body:new URLSearchParams({custName, tel})});
const doc  = new DOMParser().parseFromString(await res.text(), 'text/html');  // parse the page
const box  = doc.querySelector('.msg');                                       // .msg ok / .msg bad
const rows = [...doc.querySelectorAll('table tbody tr')];                      // the result table
```

1. `fetch` sends the same form-encoded body the `<form>` would.
2. The response is a **full HTML page** — the servlet's normal output — parsed in memory, never displayed.
3. Two things are lifted out: the `.msg` block (its `ok`/`bad` class decides green vs red) and the `<tbody>` rows.
4. They are re-rendered into the panel; the page never reloads.

Measured: `Submit-JS` → `.msg ok`, **7 rows parsed out of the HTML response**, ~243 ms, `location.href` still
`/demoEJB-Transaction/`. A duplicate name returns `.msg bad` + `ROLLED BACK — …` with the table unchanged.

**Trade-off — why a JSON endpoint is usually the better answer:**

| Client-side HTML parsing | JSON endpoint |
|---|---|
| no server change ✔ | servlet change required |
| one endpoint serves both buttons | two response modes |
| coupled to CSS class names + table structure | stable field names |
| HTTP **200 even on failure** — outcome read from prose | real status codes (409 / 500) |
| no types, no contract | machine-readable `{ok, rows[]}` |

This project deliberately takes the left column: **the servlet is back to its original single-mode form**
(`?format=json` returns HTML again), and the browser does the parsing.


## 7. How the transaction is actually managed

- `@Stateless` ⇒ **CMT** (`@TransactionManagement(CONTAINER)`) and default `TransactionAttribute.REQUIRED`.
- The container **begins** the JTA transaction before `registerCustomer` runs and **commits** it after
  the method returns normally — which is why there is no `conn.commit()` in the code.
- The `@Resource DataSource` connection is **enlisted** in that transaction. Closing it returns it to
  the pool; it does **not** commit. Only one resource is involved, so no XA is needed.
- `RuntimeException` (or any unchecked) escaping the method ⇒ rollback. A **checked** exception would
  *commit* unless you call `SessionContext.setRollbackOnly()` — the classic EJB trap.
- A `SQLException` is wrapped in `RuntimeException` on purpose: JDBC failures must roll back.

## 8. Gotchas hit while building this (all fixed)

| Symptom | Cause / fix |
|---|---|
| `class file has wrong version 55.0, should be 52.0` | Maven ran on **JDK 8** (machine `JAVA_HOME=D:\Program Files\jdk8u504-b01`). Must compile with JDK 17. |
| `ClassNotFoundException: javax.security.sasl.SaslException` | `module.xml` missing `<module name="java.security.sasl"/>`. |
| `IJ000453: Unable to get managed connection` | Symptom of the driver failing to authenticate/connect — fix the driver first. |
| `Duplicate entry 'Alice' for key 'tblCustomer.PRIMARY'` | Intentional (test B). |
| MySQL 8.4 + old driver | 8.4 removed `mysql_native_password`; use Connector/J 8.x with `allowPublicKeyRetrieval=true`. |
| Password in `standalone.xml` is plaintext | Fine for a lab; production uses an EAP credential store/vault. |

## 9. Next steps

- `@TransactionAttribute(REQUIRES_NEW)` on a second method — watch the outer transaction suspend.
- Two datasources (MySQL + H2) in one method → forces **XA** and a real 2-phase commit.
- Replace JDBC with JPA (`EntityManager.persist` ×2) — same rollback, less SQL.
- `@TransactionTimeout`-style tuning and `SessionSynchronization` are later, stateful-only topics.
