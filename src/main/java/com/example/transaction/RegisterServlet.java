package com.example.transaction;

import jakarta.ejb.EJB;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.List;

/**
 * The web front door. Holds no business logic and no SQL — it validates the two
 * fields, calls the EJB, and renders the result. @EJB gives it the bean's proxy.
 */
@WebServlet("/register")
public class RegisterServlet extends HttpServlet {

    @EJB
    private CustomerAccountBean accountBean;

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.sendRedirect("index.html");
    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws ServletException, IOException {

        req.setCharacterEncoding("UTF-8");
        resp.setContentType("text/html;charset=UTF-8");

        String custName = trim(req.getParameter("custName"));
        String tel = trim(req.getParameter("tel"));
        boolean forceFail = "true".equalsIgnoreCase(req.getParameter("forceFail"));

        String message;
        boolean ok = false;

        if (custName.isEmpty() || tel.isEmpty()) {
            message = "Both fields are required.";
        } else {
            try {
                accountBean.registerCustomer(custName, tel, forceFail);
                ok = true;
                message = "COMMITTED — 1 row in tblCustomer + 1 row in tblAccount, in one transaction.";
            } catch (Exception e) {
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                message = "ROLLED BACK — " + root.getClass().getSimpleName() + ": " + root.getMessage();
            }
        }

        // Read back from the database so the page shows the committed state, not our intentions.
        String rowsHtml;
        int accounts;
        try {
            List<CustomerAccountBean.Customer> customers = accountBean.listCustomers();
            accounts = accountBean.countAccounts();
            StringBuilder sb = new StringBuilder();
            if (customers.isEmpty()) {
                sb.append("<tr><td colspan=\"2\" class=\"empty\">(no rows)</td></tr>");
            } else {
                for (CustomerAccountBean.Customer c : customers) {
                    sb.append("<tr><td>").append(esc(c.name())).append("</td><td>")
                      .append(esc(c.tel())).append("</td></tr>");
                }
            }
            rowsHtml = sb.toString();
        } catch (Exception e) {
            rowsHtml = "<tr><td colspan=\"2\" class=\"empty\">could not read back: " + esc(e.getMessage()) + "</td></tr>";
            accounts = -1;
        }

        PrintWriter out = resp.getWriter();
        out.println("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">");
        out.println("<title>demoEJB-Transaction · result</title><style>");
        out.println("*{box-sizing:border-box}body{margin:0;font:16px/1.6 'Segoe UI',system-ui,sans-serif;"
                + "background:#0f1420;color:#e6edf7;display:flex;justify-content:center;padding:40px 20px}");
        out.println(".wrap{width:100%;max-width:640px}h1{font-size:1.35rem;margin:0 0 20px}"
                + ".card{background:#171e2e;border:1px solid #24304a;border-radius:14px;padding:22px;margin-bottom:18px}");
        out.println(".card h2{font-size:.8rem;margin:0 0 14px;color:#9fc3ff;letter-spacing:.5px;text-transform:uppercase}");
        out.println("label{display:block;font-size:.85rem;color:#8b9bb4;margin-bottom:6px}"
                + "input{width:100%;padding:11px 12px;border-radius:9px;border:1px solid #2c3a56;background:#0f1420;"
                + "color:#e6edf7;font-size:1rem;margin-bottom:14px}");
        out.println("button{background:#2f6df6;color:#fff;border:0;border-radius:9px;padding:11px 22px;font-size:.95rem;"
                + "font-weight:600;cursor:pointer}button:hover{background:#3b7bff}");
        out.println(".msg{padding:13px 15px;border-radius:10px;font-size:.93rem;margin-bottom:18px;word-break:break-word}"
                + ".ok{background:#12301f;border:1px solid #1f6b3f;color:#8ef0b0}"
                + ".bad{background:#3a1620;border:1px solid #7a2537;color:#ffb3c1}");
        out.println("table{width:100%;border-collapse:collapse;font-size:.9rem}"
                + "th,td{text-align:left;padding:9px 10px;border-bottom:1px solid #24304a}"
                + "th{color:#8b9bb4;font-weight:600;font-size:.78rem;text-transform:uppercase;letter-spacing:.4px}"
                + ".empty{color:#8b9bb4;font-style:italic}.foot{color:#8b9bb4;font-size:.85rem;margin-top:14px}"
                + "code{background:#0b101b;padding:2px 6px;border-radius:5px;color:#ffd479;font-size:.88em}");
        out.println("</style></head><body><div class=\"wrap\">");
        out.println("<h1>demoEJB-Transaction</h1>");
        out.println("<div class=\"msg " + (ok ? "ok" : "bad") + "\">" + esc(message) + "</div>");

        // The form keeps exactly two fields, as specified.
        out.println("<div class=\"card\"><h2>Register a customer</h2>");
        out.println("<form method=\"post\" action=\"register\">");
        out.println("<label for=\"custName\">Customer Name</label>");
        out.println("<input id=\"custName\" name=\"custName\" value=\"" + esc(custName) + "\" required>");
        out.println("<label for=\"tel\">Tel</label>");
        out.println("<input id=\"tel\" name=\"tel\" value=\"" + esc(tel) + "\" required>");
        out.println("<button type=\"submit\">Submit</button></form>");
        out.println("<p class=\"foot\">One Submit = <code>INSERT tblCustomer</code> + "
                + "<code>INSERT tblAccount</code> in a single JTA transaction.</p></div>");

        out.println("<div class=\"card\"><h2>Committed state in MySQL</h2>");
        out.println("<table><thead><tr><th>Cust_Name</th><th>Tel</th></tr></thead><tbody>");
        out.println(rowsHtml);
        out.println("</tbody></table>");
        out.println("<p class=\"foot\">tblAccount rows: <b>" + (accounts < 0 ? "n/a" : accounts) + "</b>"
                + " &nbsp;·&nbsp; read back through the EJB after the commit</p></div>");
        out.println("</div></body></html>");
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
