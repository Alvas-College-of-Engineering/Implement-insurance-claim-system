import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class InsuranceClaimSystem {
    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        ClaimRepository repository = new ClaimRepository();
        ClaimValidator validator = new ClaimValidator();
        ClaimProcessor processor = new ClaimProcessor();
        ClaimService service = new ClaimService(repository, validator, processor);

        service.seedDemoClaims();

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        WebController controller = new WebController(service);
        server.createContext("/", controller);
        server.setExecutor(null);
        server.start();
        System.out.println("Insurance Claim System running at http://localhost:" + port + "/");
    }
}

enum ClaimStatus {
    SUBMITTED("Submitted"),
    APPROVED("Approved"),
    REJECTED("Rejected");

    private final String label;

    ClaimStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}

class Policyholder {
    private final String fullName;
    private final String policyNumber;
    private final String email;

    public Policyholder(String fullName, String policyNumber, String email) {
        this.fullName = fullName;
        this.policyNumber = policyNumber;
        this.email = email;
    }

    public String getFullName() {
        return fullName;
    }

    public String getPolicyNumber() {
        return policyNumber;
    }

    public String getEmail() {
        return email;
    }
}

class Claim {
    private final int id;
    private final Policyholder policyholder;
    private final String claimType;
    private final BigDecimal claimAmount;
    private final LocalDate incidentDate;
    private final String description;
    private final LocalDateTime submittedAt;
    private ClaimStatus status;
    private String decisionMessage;

    public Claim(int id, Policyholder policyholder, String claimType, BigDecimal claimAmount,
                 LocalDate incidentDate, String description) {
        this.id = id;
        this.policyholder = policyholder;
        this.claimType = claimType;
        this.claimAmount = claimAmount;
        this.incidentDate = incidentDate;
        this.description = description;
        this.submittedAt = LocalDateTime.now();
        this.status = ClaimStatus.SUBMITTED;
        this.decisionMessage = "Claim is waiting for automated review.";
    }

    public int getId() {
        return id;
    }

    public Policyholder getPolicyholder() {
        return policyholder;
    }

    public String getClaimType() {
        return claimType;
    }

    public BigDecimal getClaimAmount() {
        return claimAmount;
    }

    public LocalDate getIncidentDate() {
        return incidentDate;
    }

    public String getDescription() {
        return description;
    }

    public LocalDateTime getSubmittedAt() {
        return submittedAt;
    }

    public ClaimStatus getStatus() {
        return status;
    }

    public String getDecisionMessage() {
        return decisionMessage;
    }

    public void approve(String message) {
        status = ClaimStatus.APPROVED;
        decisionMessage = message;
    }

    public void reject(String message) {
        status = ClaimStatus.REJECTED;
        decisionMessage = message;
    }
}

class ClaimValidator {
    public List<String> validate(String name, String policyNumber, String email, String claimType,
                                 String amountText, String incidentDateText, String description) {
        List<String> errors = new ArrayList<>();

        if (isBlank(name)) {
            errors.add("Policyholder name is required.");
        }
        if (isBlank(policyNumber) || !policyNumber.matches("POL-[0-9]{4,8}")) {
            errors.add("Policy number must match POL- followed by 4 to 8 digits.");
        }
        if (isBlank(email) || !email.contains("@") || !email.contains(".")) {
            errors.add("A valid email address is required.");
        }
        if (isBlank(claimType)) {
            errors.add("Please choose a claim type.");
        }
        if (parseAmount(amountText) == null) {
            errors.add("Claim amount must be a positive number.");
        }
        LocalDate incidentDate = parseDate(incidentDateText);
        if (incidentDate == null) {
            errors.add("Incident date must be a valid date.");
        } else if (incidentDate.isAfter(LocalDate.now())) {
            errors.add("Incident date cannot be in the future.");
        }
        if (isBlank(description) || description.trim().length() < 15) {
            errors.add("Description must contain at least 15 characters.");
        }

        return errors;
    }

    public BigDecimal parseAmount(String amountText) {
        try {
            BigDecimal amount = new BigDecimal(amountText.trim());
            return amount.compareTo(BigDecimal.ZERO) > 0 ? amount : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }

    public LocalDate parseDate(String dateText) {
        try {
            return LocalDate.parse(dateText);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}

class ClaimProcessor {
    private static final BigDecimal AUTO_APPROVAL_LIMIT = new BigDecimal("50000");

    public void process(Claim claim) {
        if (claim.getDescription().toLowerCase().contains("fraud")) {
            claim.reject("Rejected because the description contains a fraud risk keyword.");
        } else if (claim.getClaimAmount().compareTo(AUTO_APPROVAL_LIMIT) <= 0) {
            claim.approve("Approved automatically because the amount is within policy limits.");
        } else {
            claim.reject("Rejected for manual review because the amount exceeds 50,000.");
        }
    }
}

class ClaimRepository {
    private final AtomicInteger sequence = new AtomicInteger(1000);
    private final Map<Integer, Claim> claims = new LinkedHashMap<>();

    public synchronized Claim save(Claim claim) {
        claims.put(claim.getId(), claim);
        return claim;
    }

    public synchronized List<Claim> findAll() {
        List<Claim> result = new ArrayList<>(claims.values());
        Collections.reverse(result);
        return result;
    }

    public int nextId() {
        return sequence.incrementAndGet();
    }
}

class ClaimService {
    private final ClaimRepository repository;
    private final ClaimValidator validator;
    private final ClaimProcessor processor;

    public ClaimService(ClaimRepository repository, ClaimValidator validator, ClaimProcessor processor) {
        this.repository = repository;
        this.validator = validator;
        this.processor = processor;
    }

    public ClaimResult submit(Map<String, String> form) {
        String name = form.getOrDefault("name", "");
        String policyNumber = form.getOrDefault("policyNumber", "");
        String email = form.getOrDefault("email", "");
        String claimType = form.getOrDefault("claimType", "");
        String amount = form.getOrDefault("amount", "");
        String incidentDate = form.getOrDefault("incidentDate", "");
        String description = form.getOrDefault("description", "");

        List<String> errors = validator.validate(name, policyNumber, email, claimType, amount, incidentDate, description);
        if (!errors.isEmpty()) {
            return ClaimResult.failed(errors);
        }

        Policyholder policyholder = new Policyholder(name.trim(), policyNumber.trim().toUpperCase(), email.trim());
        Claim claim = new Claim(repository.nextId(), policyholder, claimType, validator.parseAmount(amount),
                validator.parseDate(incidentDate), description.trim());
        processor.process(claim);
        repository.save(claim);
        return ClaimResult.success(claim);
    }

    public List<Claim> allClaims() {
        return repository.findAll();
    }

    public void seedDemoClaims() {
        Map<String, String> approved = new LinkedHashMap<>();
        approved.put("name", "Aarav Mehta");
        approved.put("policyNumber", "POL-120045");
        approved.put("email", "aarav@example.com");
        approved.put("claimType", "Health");
        approved.put("amount", "18500");
        approved.put("incidentDate", LocalDate.now().minusDays(4).toString());
        approved.put("description", "Hospital treatment and prescription expenses.");
        submit(approved);

        Map<String, String> rejected = new LinkedHashMap<>();
        rejected.put("name", "Nisha Rao");
        rejected.put("policyNumber", "POL-98551");
        rejected.put("email", "nisha@example.com");
        rejected.put("claimType", "Vehicle");
        rejected.put("amount", "76000");
        rejected.put("incidentDate", LocalDate.now().minusDays(2).toString());
        rejected.put("description", "Major collision repair claim with full body work.");
        submit(rejected);
    }
}

class ClaimResult {
    private final Claim claim;
    private final List<String> errors;

    private ClaimResult(Claim claim, List<String> errors) {
        this.claim = claim;
        this.errors = errors;
    }

    public static ClaimResult success(Claim claim) {
        return new ClaimResult(claim, Collections.emptyList());
    }

    public static ClaimResult failed(List<String> errors) {
        return new ClaimResult(null, errors);
    }

    public boolean isSuccess() {
        return claim != null;
    }

    public Claim getClaim() {
        return claim;
    }

    public List<String> getErrors() {
        return errors;
    }
}

class WebController implements HttpHandler {
    private final ClaimService service;
    private final DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a");

    public WebController(ClaimService service) {
        this.service = service;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        if ("GET".equalsIgnoreCase(method) && "/".equals(path)) {
            send(exchange, renderPage(null, Collections.emptyMap(), Collections.emptyList()));
            return;
        }
        if ("POST".equalsIgnoreCase(method) && "/submit".equals(path)) {
            Map<String, String> form = parseForm(exchange);
            ClaimResult result = service.submit(form);
            if (result.isSuccess()) {
                send(exchange, renderPage(result.getClaim(), Collections.emptyMap(), Collections.emptyList()));
            } else {
                send(exchange, renderPage(null, form, result.getErrors()));
            }
            return;
        }
        redirectHome(exchange);
    }

    private String renderPage(Claim latestClaim, Map<String, String> form, List<String> errors) {
        StringBuilder html = new StringBuilder();
        html.append("<!doctype html><html><head><meta charset='utf-8'>");
        html.append("<meta name='viewport' content='width=device-width,initial-scale=1'>");
        html.append("<title>Insurance Claim System</title><style>").append(styles()).append("</style></head><body>");
        html.append("<main class='shell'>");
        html.append("<section class='hero'><div><p class='eyebrow'>Java Dynamic Web Project</p>");
        html.append("<h1>Insurance Claim System</h1>");
        html.append("<p class='sub'>Submit claims, validate policy details, process approvals or rejections, and track every claim in one clean dashboard.</p></div>");
        html.append("<div class='metrics'><span>").append(service.allClaims().size()).append("</span><small>Total claims</small></div></section>");

        if (!errors.isEmpty()) {
            html.append("<section class='notice error'><strong>Please fix these details:</strong><ul>");
            for (String error : errors) {
                html.append("<li>").append(escape(error)).append("</li>");
            }
            html.append("</ul></section>");
        }

        if (latestClaim != null) {
            html.append("<section class='notice success'><strong>Claim #").append(latestClaim.getId()).append(" ");
            html.append(latestClaim.getStatus().label()).append("</strong><p>");
            html.append(escape(latestClaim.getDecisionMessage())).append("</p></section>");
        }

        html.append("<section class='grid'>");
        html.append("<form class='panel form' method='post' action='/submit'>");
        html.append("<h2>Submit a Claim</h2>");
        input(html, "Full name", "name", "text", form, "Riya Sharma");
        input(html, "Policy number", "policyNumber", "text", form, "POL-123456");
        input(html, "Email", "email", "email", form, "riya@example.com");
        html.append("<label>Claim type<select name='claimType'>");
        option(html, "", "Choose type", form.getOrDefault("claimType", ""));
        option(html, "Health", "Health", form.getOrDefault("claimType", ""));
        option(html, "Vehicle", "Vehicle", form.getOrDefault("claimType", ""));
        option(html, "Home", "Home", form.getOrDefault("claimType", ""));
        option(html, "Travel", "Travel", form.getOrDefault("claimType", ""));
        html.append("</select></label>");
        input(html, "Claim amount", "amount", "number", form, "25000");
        input(html, "Incident date", "incidentDate", "date", form, LocalDate.now().toString());
        html.append("<label>Description<textarea name='description' placeholder='Briefly describe the incident and supporting details'>");
        html.append(escape(form.getOrDefault("description", ""))).append("</textarea></label>");
        html.append("<button type='submit'>Submit and Process Claim</button>");
        html.append("</form>");

        html.append("<section class='panel'><div class='table-head'><h2>Claim Status</h2><span>Live in-memory records</span></div>");
        html.append("<div class='claims'>");
        for (Claim claim : service.allClaims()) {
            html.append("<article class='claim ").append(claim.getStatus().name().toLowerCase()).append("'>");
            html.append("<div><strong>#").append(claim.getId()).append(" ").append(escape(claim.getPolicyholder().getFullName())).append("</strong>");
            html.append("<small>").append(escape(claim.getClaimType())).append(" / ").append(formatMoney(claim.getClaimAmount())).append("</small></div>");
            html.append("<span class='badge'>").append(claim.getStatus().label()).append("</span>");
            html.append("<p>").append(escape(claim.getDecisionMessage())).append("</p>");
            html.append("<footer>Policy ").append(escape(claim.getPolicyholder().getPolicyNumber())).append(" / Incident ");
            html.append(claim.getIncidentDate()).append(" / Submitted ").append(dateTimeFormatter.format(claim.getSubmittedAt())).append("</footer>");
            html.append("</article>");
        }
        html.append("</div></section></section></main></body></html>");
        return html.toString();
    }

    private void input(StringBuilder html, String label, String name, String type, Map<String, String> form, String placeholder) {
        html.append("<label>").append(label).append("<input name='").append(name).append("' type='").append(type);
        html.append("' placeholder='").append(escape(placeholder)).append("' value='").append(escape(form.getOrDefault(name, ""))).append("'></label>");
    }

    private void option(StringBuilder html, String value, String label, String selectedValue) {
        html.append("<option value='").append(escape(value)).append("'");
        if (value.equals(selectedValue)) {
            html.append(" selected");
        }
        html.append(">").append(escape(label)).append("</option>");
    }

    private Map<String, String> parseForm(HttpExchange exchange) throws IOException {
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : body.toString().split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            String key = decode(parts[0]);
            String value = parts.length > 1 ? decode(parts[1]) : "";
            result.put(key, value);
        }
        return result;
    }

    private String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private void send(HttpExchange exchange, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private void redirectHome(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().set("Location", "/");
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private String formatMoney(BigDecimal amount) {
        return "Rs. " + amount.toPlainString();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String styles() {
        return """
                *{box-sizing:border-box}body{margin:0;font-family:Arial,Helvetica,sans-serif;background:#f5f7fb;color:#18202f}\
                .shell{width:min(1180px,94vw);margin:0 auto;padding:28px 0 44px}.hero{display:flex;justify-content:space-between;gap:24px;align-items:end;padding:30px 0 26px;border-bottom:1px solid #d9e1ef}\
                .eyebrow{margin:0 0 10px;color:#176b5c;text-transform:uppercase;font-size:12px;font-weight:700;letter-spacing:0}h1{margin:0;font-size:clamp(32px,5vw,60px);line-height:1.02;color:#111827;letter-spacing:0}\
                .sub{max-width:720px;margin:14px 0 0;color:#516072;font-size:17px;line-height:1.6}.metrics{min-width:150px;background:#111827;color:white;padding:18px;border-radius:8px;text-align:center;box-shadow:0 12px 30px #11182722}\
                .metrics span{display:block;font-size:36px;font-weight:800}.metrics small{color:#cbd5e1}.grid{display:grid;grid-template-columns:420px 1fr;gap:22px;margin-top:22px}.panel{background:white;border:1px solid #dce4ef;border-radius:8px;padding:22px;box-shadow:0 10px 24px #26364a12}\
                h2{margin:0 0 18px;font-size:22px}.form{display:grid;gap:14px;align-self:start}label{display:grid;gap:7px;color:#384556;font-size:14px;font-weight:700}input,select,textarea{width:100%;border:1px solid #cbd5e1;border-radius:6px;padding:12px 13px;font:inherit;background:#fbfdff;color:#172033}\
                textarea{min-height:105px;resize:vertical}button{border:0;border-radius:6px;padding:13px 16px;background:#176b5c;color:white;font-weight:800;font-size:15px;cursor:pointer}button:hover{background:#105447}.notice{margin-top:18px;border-radius:8px;padding:16px 18px}.notice p{margin:8px 0 0}.notice ul{margin:10px 0 0;padding-left:20px}.error{background:#fff1f2;border:1px solid #fecdd3;color:#9f1239}.success{background:#ecfdf5;border:1px solid #bbf7d0;color:#14532d}\
                .table-head{display:flex;justify-content:space-between;gap:16px;align-items:center}.table-head span{color:#64748b;font-size:13px}.claims{display:grid;gap:12px}.claim{display:grid;grid-template-columns:1fr auto;gap:8px 14px;border:1px solid #e2e8f0;border-left-width:5px;border-radius:8px;padding:15px;background:#fcfdff}.claim.approved{border-left-color:#16a34a}.claim.rejected{border-left-color:#dc2626}.claim.submitted{border-left-color:#2563eb}\
                .claim strong{display:block;font-size:16px}.claim small{display:block;color:#64748b;margin-top:4px}.claim p{grid-column:1/-1;margin:2px 0;color:#334155}.claim footer{grid-column:1/-1;color:#697789;font-size:12px}.badge{height:28px;padding:6px 10px;border-radius:999px;background:#e8eef7;color:#1f2937;font-weight:800;font-size:12px}\
                @media(max-width:860px){.hero{align-items:start;flex-direction:column}.grid{grid-template-columns:1fr}.metrics{width:100%;text-align:left}.table-head{display:block}.claim{grid-template-columns:1fr}.badge{width:max-content}}\
                """;
    }
}
