package com.function;

import com.microsoft.azure.functions.ExecutionContext;
import com.microsoft.azure.functions.HttpMethod;
import com.microsoft.azure.functions.HttpRequestMessage;
import com.microsoft.azure.functions.HttpResponseMessage;
import com.microsoft.azure.functions.HttpStatus;
import com.microsoft.azure.functions.annotation.AuthorizationLevel;
import com.microsoft.azure.functions.annotation.BindingName;
import com.microsoft.azure.functions.annotation.FunctionName;
import com.microsoft.azure.functions.annotation.HttpTrigger;
import com.rabbitmq.client.ConnectionFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.json.JSONObject;
import org.mindrot.jbcrypt.BCrypt;

/**
 * Azure Functions with HTTP Trigger.
 */
public class Function {
    /**
     * This function listens at endpoint "/api/HttpExample". Two ways to invoke it
     * using "curl" command in bash:
     * 1. curl -d "HTTP Body" {your host}/api/HttpExample
     * 2. curl "{your host}/api/HttpExample?name=HTTP%20Query"
     */

    private static final Logger logger = Logger.getLogger(Function.class.getName());
    private static final String DB_URL = "jdbc:mysql://leisadb.mysql.database.azure.com:3306/leisa";
    private static final String DB_USER = "lei";
    private static final String DB_PASSWORD = "mahirgamal123#";

    private String username, password, queueName;

    static {
        try {
            // Load the JDBC driver
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
    }

    @FunctionName("deleteService")
    public HttpResponseMessage run(
            @HttpTrigger(name = "req", methods = {
                    HttpMethod.DELETE }, route = "delete/{id}", authLevel = AuthorizationLevel.ANONYMOUS) HttpRequestMessage<Optional<User>> request,
            @BindingName("id") Long id,
            final ExecutionContext context) throws SQLException {
        logger.info("Received request to delete user with ID: " + id);

        if (isAuthorized(request, id)) {
            boolean deleteResult = deleteUser(id);
            if (deleteResult) {
                logger.info("User with ID " + id + " deleted successfully.");
                return request.createResponseBuilder(HttpStatus.OK).body("Deleted").build();
            } else {
                logger.warning("Failed to delete user with ID " + id);
                return request.createResponseBuilder(HttpStatus.INTERNAL_SERVER_ERROR).body("Error deleting user")
                        .build();
            }
        } else {
            logger.warning("Authorization failed for user with ID " + id);
            return request.createResponseBuilder(HttpStatus.UNAUTHORIZED).body("Unauthorized").build();
        }
    }

    private boolean isAuthorized(HttpRequestMessage<Optional<User>> request, Long id) {
        // Parse the Authorization header
        final String authHeader = request.getHeaders().get("authorization");
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return false;
        }

        // Extract and decode username and password
        String base64Credentials = authHeader.substring("Basic ".length()).trim();
        byte[] credDecoded = Base64.getDecoder().decode(base64Credentials);
        String credentials = new String(credDecoded);

        // credentials = username:password
        final String[] values = credentials.split(":", 2);

        if (values.length != 2) {
            return false; // Incorrect format of the header
        }

        username = values[0];
        password = values[1];

        String sql = "SELECT * FROM users WHERE id=?";

        try (java.sql.Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setLong(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    // Compare the provided password with the hashed password in the database
                    if (BCrypt.checkpw(password, rs.getString("password")) && username.equals(rs.getString("username")))
                    {
                        queueName=rs.getString("queuename");
                        return true;
                    }
                    else

                        return false;
                } else
                    return false;
            }
        } catch (SQLException e) {
            // Handle exceptions (log it or throw as needed)
            e.printStackTrace();
        }

        return false;

    }

    private boolean deleteUser(Long id) throws SQLException {
        if (rabbitmqDeleteUser(username)) {
            String sql = "DELETE FROM users WHERE id = ?";

            try (java.sql.Connection conn = DriverManager.getConnection(DB_URL, DB_USER, DB_PASSWORD);
                    PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setLong(1, id);
                int affectedRows = stmt.executeUpdate();
                if (affectedRows == 0) {
                    logger.warning("No user found with ID " + id);
                    return false;
                }
                logger.info("Database deletion successful for user ID: " + id);
                return true;
            }
        } else {
            logger.warning("Failed to delete RabbitMQ user with username: " + username);
            return false;
        }
    }

    public boolean rabbitmqDeleteUser(String brokerUsername) {
        try {
            // Read the JSON file (e.g., 'config.json')
            // You'll need to use a library like Jackson or Gson for JSON parsing in Java
            // Here, we assume that you have a Config class to represent the configuration
            // object

            InputStream inputStream = getClass().getClassLoader().getResourceAsStream("rabbitmqconfig.json");
            if (inputStream == null) {
                throw new IOException("rabbitmqconfig.json file not found in resources");
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            String configJSON = reader.lines().collect(Collectors.joining("\n"));
            JSONObject config = new JSONObject(configJSON);

            // Configuration values
            String brokerType = config.getString("brokerType");
            String brokerProtocol = config.getString("brokerProtocol");
            String brokerHost = config.getString("brokerHost");
            int brokerPort = config.getInt("brokerPort");
            String username = config.getString("brokerUsername");
            String password = config.getString("brokerPassword");

            logger.info("Broker Type: " + brokerType);
            logger.info("Broker Protocol: " + brokerProtocol);
            logger.info("Broker Host: " + brokerHost);
            logger.info("Broker Port: " + brokerPort);
            logger.info("Username: " + username);
            logger.info("Password: " + password);

            // Create a connection to RabbitMQ
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(brokerHost);
            factory.setPort(brokerPort);
            factory.setUsername(username);
            factory.setPassword(password);

            String brokerApiBaseUrl = config.getString("apiUrl");

            logger.info("brokerApiBaseUrl: " + brokerApiBaseUrl);

            String auth = username + ":" + password;
            String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes());

            HttpClient client = HttpClient.newBuilder()
                    .build();

            HttpRequest deleteUserRequest = HttpRequest.newBuilder()
                    .uri(URI.create(brokerApiBaseUrl + "/users/" + brokerUsername))
                    .timeout(Duration.ofMinutes(1))
                    .header("Authorization", "Basic " + encodedAuth)
                    .DELETE()
                    .build();

            HttpResponse<String> deleteUserResponse = client.send(deleteUserRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (!(deleteUserResponse.statusCode() == 204 || deleteUserResponse.statusCode() == 200)) {
                logger.warning("Failed to delete user. Status code: " + deleteUserResponse.statusCode());
                return false;
            }

            // Delete the queue
            HttpRequest deleteQueueRequest = HttpRequest.newBuilder()
                    .uri(URI.create(brokerApiBaseUrl + "/queues/%2F/" + queueName)) // %2F is URL-encoded "/"
                    .timeout(Duration.ofMinutes(1))
                    .header("Authorization", "Basic " + encodedAuth)
                    .DELETE()
                    .build();

            HttpResponse<String> deleteQueueResponse = client.send(deleteQueueRequest,
                    HttpResponse.BodyHandlers.ofString());

            if (!(deleteQueueResponse.statusCode() == 204 || deleteQueueResponse.statusCode() == 200)) {
                logger.warning("Failed to delete queue. Status code: " + deleteQueueResponse.statusCode());
                return false;
            }

            logger.info("User and associated queue deleted successfully");
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            logger.warning("Failed to delete user and queue. Error: " + e.getMessage());
            return false;
        }
    }

}
