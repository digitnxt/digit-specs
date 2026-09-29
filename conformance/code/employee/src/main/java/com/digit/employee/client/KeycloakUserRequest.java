package com.digit.employee.client;

/**
 * Minimal Keycloak user representation for {@link KeycloakClient#createUser}. Mirrors the Go
 * {@code keycloak.CreateUserRequest}. Roles are NOT set here — Keycloak's single-user create endpoint
 * does not reliably apply them; they are assigned separately via {@code assignRealmRoles}. Fields are
 * package-private and read directly by {@link KeycloakClient} (same package) when building the JSON body.
 */
public class KeycloakUserRequest {

    final String username;
    final String email;
    final String firstName;
    final String lastName;
    final String password;
    final boolean emailVerified;

    public KeycloakUserRequest(String username, String email, String firstName, String lastName,
                               String password, boolean emailVerified) {
        this.username = username;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.password = password;
        this.emailVerified = emailVerified;
    }
}