package com.example.transaction;

import jakarta.annotation.Resource;
import jakarta.ejb.Stateless;
import jakarta.transaction.Status;
import jakarta.transaction.TransactionSynchronizationRegistry;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * THE EJB — and the whole point of this project.
 *
 * No transaction code anywhere: no connection.commit(), no rollback(), no try/catch
 * around a transaction boundary. Two INSERTs into two different tables are executed,
 * and the EJB container guarantees they are ONE unit of work:
 *
 *   - a RuntimeException escapes the method  -> BOTH inserts roll back
 *   - the method returns normally            -> BOTH inserts commit
 *
 * Why it works: @Stateless gets container-managed transactions (CMT) by default and
 * every business method runs with TransactionAttribute.REQUIRED. The container starts
 * the JTA transaction BEFORE the first line of the method and commits/rolls back AFTER
 * the method exits.
 */
@Stateless
public class CustomerAccountBean {

    /** JNDI name of the MySQL datasource defined in standalone.xml. */
    public static final String DS_JNDI = "java:jboss/datasources/DemoEJBMySQLDS";

    /**
     * The container injects a pooled DataSource — we never create connections ourselves.
     * The Connection handed out here is ENLISTED in the current JTA transaction, so
     * closing it returns it to the pool while the transaction is still open.
     */
    @Resource(lookup = DS_JNDI)
    private DataSource dataSource;

    /**
     * Lets us log the JTA transaction status from inside the method — proof that the
     * container already opened a transaction before our first statement.
     */
    @Resource
    private TransactionSynchronizationRegistry tsr;

    /** Small read-only view for the page. */
    public record Customer(String name, String tel) {}

    /**
     * Inserts one tblCustomer row AND one tblAccount row, atomically.
     *
     * @param custName     customer name (primary key of tblCustomer)
     * @param tel          telephone
     * @param forceFailure when true, throws AFTER both inserts — used to prove rollback
     */
    public void registerCustomer(String custName, String tel, boolean forceFailure) {

        System.out.println("[CustomerAccountBean] -> tx status at entry = "
                + tsr.getTransactionStatus() + " (" + Status.STATUS_ACTIVE + " = ACTIVE, "
                + "already started by the container)");

        final String sqlCustomer = "INSERT INTO tblCustomer (Cust_Name, Tel) VALUES (?, ?)";
        final String sqlAccount = "INSERT INTO tblAccount (Cust_Name, Deposit_Withdraw, Amount) VALUES (?, 'D', 0.00)";

        try (Connection conn = dataSource.getConnection()) {

            int customerRows;
            try (PreparedStatement ps = conn.prepareStatement(sqlCustomer)) {
                ps.setString(1, custName);
                ps.setString(2, tel);
                customerRows = ps.executeUpdate();
            }

            int accountRows;
            try (PreparedStatement ps = conn.prepareStatement(sqlAccount)) {
                ps.setString(1, custName);
                accountRows = ps.executeUpdate();
            }

            System.out.println("[CustomerAccountBean] tblCustomer rows=" + customerRows
                    + ", tblAccount rows=" + accountRows
                    + " (nothing is committed yet — the container commits when this method returns)");

            if (forceFailure) {
                // Unchecked exception escaping the method => container marks rollback.
                // Both INSERTs above are undone, even though they succeeded.
                throw new IllegalStateException(
                        "FORCED FAILURE after both inserts — the container must roll back both rows");
            }

            // No conn.commit() here. Closing the connection does NOT commit; the
            // transaction manager commits after the method returns successfully.

        } catch (SQLException e) {
            // Convert to unchecked => rollback. (A checked exception would COMMIT by default,
            // unless the transaction is marked rollback-only — a classic EJB gotcha.)
            throw new RuntimeException("Database error: " + e.getMessage(), e);
        }
    }

    /** Everything currently in tblCustomer, for the result page. */
    public List<Customer> listCustomers() {
        List<Customer> result = new ArrayList<>();
        String sql = "SELECT Cust_Name, Tel FROM tblCustomer ORDER BY Cust_Name";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(new Customer(rs.getString(1), rs.getString(2)));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Database error: " + e.getMessage(), e);
        }
        return result;
    }

    /** How many rows are in tblAccount — proves the second table took part in the same tx. */
    public int countAccounts() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM tblAccount");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (SQLException e) {
            throw new RuntimeException("Database error: " + e.getMessage(), e);
        }
    }
}
