package com.khodecamp;

import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.forms.login.freemarker.model.TotpBean;
import org.keycloak.models.*;
import org.keycloak.models.credential.OTPCredentialModel;
import org.keycloak.models.utils.HmacOTP;
import org.keycloak.models.utils.TimeBasedOTP;
import org.keycloak.services.managers.AppAuthManager;
import org.keycloak.services.managers.AuthenticationManager.AuthResult;

import java.util.Map;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@JBossLog
public class KhodeResourceService {

    private final KeycloakSession session;

    // Response codes
    private static final int CODE_SUCCESS = 0;
    private static final int CODE_INVALID_USER_ID = 1;
    private static final int CODE_INVALID_CODE = 2;
    private static final int CODE_TOTP_NOT_ENABLED = 3;
    private static final int CODE_TOTP_ALREADY_ENABLED = 4;
    private static final int CODE_SERVER_ERROR = 5;
    private static final int CODE_TOTP_SETUP_REQUIRED = 6;
    private static final int CODE_INVALID_TOTP = 7;
    private static final int CODE_OPERATION_FAILED = 8;

    // get user and from the session via bearer token
    private AuthResult checkAuth() {
        AuthResult auth = new AppAuthManager.BearerTokenAuthenticator(session).authenticate();

        if (auth == null) {
            throw new NotAuthorizedException("Bearer");
        }

        return auth;
    }

    // Check permissions and get user
    private UserModel checkPermissionsAndGetUser(final String userid) {

        // Check if the request is authenticated
        final UserModel user = this.session.users().getUserById(this.session.getContext().getRealm(), userid);
        if (user == null) {
            throw new ForbiddenException("invalid user");
        }
        if (user.getServiceAccountClientLink() != null) {
            throw new ForbiddenException("Service account not allowed");
        }

        return user;
    }

    // New private helper methods
    private Response validateUserId(String userid) {
        if (userid == null || userid.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "error", "Invalid user ID",
                            "code", CODE_INVALID_USER_ID
                    ))
                    .build();
        }
        return null;
    }

    private Response validateTotpCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Code is required", "code", CODE_INVALID_CODE))
                    .build();
        }
        return null;
    }

    private Response checkTotpEnabled(UserModel user, boolean shouldBeEnabled) {
        var totpCredentials = user.credentialManager()
                .getStoredCredentialsByTypeStream(OTPCredentialModel.TYPE)
                .toList();

        boolean hasTotp = !totpCredentials.isEmpty();

        if (shouldBeEnabled && !hasTotp) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "error", "TOTP is not enabled for this user",
                            "code", CODE_TOTP_NOT_ENABLED
                    ))
                    .build();
        }

        if (!shouldBeEnabled && hasTotp) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "error", "TOTP is already configured for this user",
                            "code", CODE_TOTP_ALREADY_ENABLED
                    ))
                    .build();
        }

        return null;
    }

    private Response handleServerError(String operation, String userid, Exception e) {
        log.error("Error while " + operation + " for user: " + userid, e);
        return Response.serverError()
                .entity(Map.of("error", "Internal server error", "code", CODE_SERVER_ERROR))
                .build();
    }

    public Response isTotpConfigured(final String userid) {
        Response validation = validateUserId(userid);
        if (validation != null) return validation;

        try {
            final UserModel user = checkPermissionsAndGetUser(userid);
            boolean hasTotp = user.credentialManager()
                    .getStoredCredentialsByTypeStream(OTPCredentialModel.TYPE)
                    .findAny()
                    .isPresent();

            return Response.ok(Map.of(
                    "configured", hasTotp,
                    "message", hasTotp ? "TOTP is configured for this user" : "TOTP is not configured for this user",
                    "userId", userid,
                    "code", CODE_SUCCESS
            )).build();
        } catch (Exception e) {
            return handleServerError("checking TOTP configuration", userid, e);
        }
    }

    public Response setupTotp(@PathParam("user_id") final String userid) {
        Response validation = validateUserId(userid);
        if (validation != null) return validation;

        try {
            final UserModel user = checkPermissionsAndGetUser(userid);

            // Check if TOTP is already configured
            validation = checkTotpEnabled(user, false);
            if (validation != null) return validation;

            final RealmModel realm = session.getContext().getRealm();
            TotpBean totpBean = new TotpBean(session, realm, user, null);
            user.setSingleAttribute("temp_totp_secret", totpBean.getTotpSecret());
            OTPPolicy otpPolicy = realm.getOTPPolicy();

            return Response.ok(Map.of(
                    "secret", totpBean.getTotpSecretEncoded(),
                    "qrCode", totpBean.getTotpSecretQrCode(),
                    "policy", Map.of(
                            "algorithm", otpPolicy.getAlgorithm(),
                            "digits", otpPolicy.getDigits(),
                            "period", otpPolicy.getPeriod(),
                            "type", otpPolicy.getType()
                    ),
                    "supportedApplications", totpBean.getSupportedApplications(),
                    "userId", userid,
                    "code", CODE_SUCCESS
            )).build();
        } catch (Exception e) {
            return handleServerError("setting up TOTP", userid, e);
        }
    }

    public Response verifyAndEnableTotp(final String userid, Map<String, String> data) {
        Response validation = validateUserId(userid);
        if (validation != null) return validation;

        final UserModel user = checkPermissionsAndGetUser(userid);
        final RealmModel realm = session.getContext().getRealm();
        String code = data.get("code");

        validation = validateTotpCode(code);
        if (validation != null) return validation;

        String totpSecret = user.getFirstAttribute("temp_totp_secret");
        if (totpSecret == null) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of(
                            "error", "TOTP setup required",
                            "code", CODE_TOTP_SETUP_REQUIRED
                    )).build();
        }

        OTPPolicy otpPolicy = realm.getOTPPolicy();
        HmacOTP hmacOTP = new HmacOTP(
                otpPolicy.getDigits(),
                otpPolicy.getAlgorithm(),
                1  // lookAroundWindow - number of intervals to check
        );

        // Calculate current counter based on current time
        long currentTimeSeconds = System.currentTimeMillis() / 1000;
        int currentCounter = (int) (currentTimeSeconds / otpPolicy.getPeriod());

        // Validate the code
        int newCounter = hmacOTP.validateHOTP(code, totpSecret, currentCounter - 1);
        boolean validCode = newCounter > 0;

        if (!validCode) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(Map.of("error", "Invalid code", "code", CODE_INVALID_TOTP))
                    .build();
        }

        // Remove temporary secret
        user.removeAttribute("temp_totp_secret");

        // Create OTP credential
        OTPCredentialModel otpCredential = OTPCredentialModel.createTOTP(
                totpSecret,
                otpPolicy.getDigits(),
                otpPolicy.getPeriod(),
                otpPolicy.getAlgorithm()
        );

        // Store the credential
        user.credentialManager().createStoredCredential(otpCredential);

        return Response.ok(Map.of(
                "message", "TOTP enabled successfully",
                "enabled", true,
                "code", CODE_SUCCESS
        )).build();
    }

    public Response getTotpStatus(final String userid) {
        Response validation = validateUserId(userid);
        if (validation != null) return validation;

        try {
            final UserModel user = checkPermissionsAndGetUser(userid);
            final RealmModel realm = session.getContext().getRealm();
            TotpBean totpBean = new TotpBean(session, realm, user, null);

            return Response.ok(Map.of(
                    "enabled", totpBean.isEnabled(),
                    "credentials", totpBean.getOtpCredentials().stream()
                            .map(credential -> Map.of(
                                    "id", credential.getId(),
                                    "type", credential.getType(),
                                    "createdDate", credential.getCreatedDate()
                            ))
                            .collect(Collectors.toList()),
                    "userId", userid,
                    "code", CODE_SUCCESS
            )).build();
        } catch (Exception e) {
            return handleServerError("getting TOTP status", userid, e);
        }
    }

    public Response validateTotp(final String userid, Map<String, String> data) {
        Response validation = validateUserId(userid);
        if (validation != null) return validation;

        try {
            validation = validateTotpCode(data.get("code"));
            if (validation != null) return validation;

            final UserModel user = checkPermissionsAndGetUser(userid);
            validation = checkTotpEnabled(user, true);
            if (validation != null) return validation;

            final RealmModel realm = session.getContext().getRealm();
            OTPPolicy otpPolicy = realm.getOTPPolicy();
            TimeBasedOTP timeBasedOTP = new TimeBasedOTP(
                    otpPolicy.getAlgorithm(),
                    otpPolicy.getDigits(),
                    otpPolicy.getPeriod(),
                    0
            );

            var totpCredentials = user.credentialManager()
                    .getStoredCredentialsByTypeStream(OTPCredentialModel.TYPE)
                    .toList();
            OTPCredentialModel credential = OTPCredentialModel.createFromCredentialModel(totpCredentials.getFirst());

            if (!timeBasedOTP.validateTOTP(data.get("code"), credential.getDecodedSecret())) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Invalid code", "code", CODE_INVALID_TOTP))
                        .build();
            }

            return Response.ok(Map.of(
                    "message", "TOTP code validated successfully",
                    "valid", true,
                    "userId", userid,
                    "code", CODE_SUCCESS
            )).build();
        } catch (Exception e) {
            return handleServerError("validating TOTP code", userid, e);
        }
    }

    public Response disableTotp(final String userid) {
        Response validation = validateUserId(userid);
        if (validation != null) return validation;

        try {
            final UserModel user = checkPermissionsAndGetUser(userid);

            // Get all TOTP credentials
            var totpCredentials = user.credentialManager()
                    .getStoredCredentialsByTypeStream(OTPCredentialModel.TYPE)
                    .toList();

            if (totpCredentials.isEmpty()) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of(
                                "error", "TOTP is not enabled for this user",
                                "code", CODE_TOTP_NOT_ENABLED
                        ))
                        .build();
            }

            // Remove all TOTP credentials
            for (var credential : totpCredentials) {
                try {
                    user.credentialManager().removeStoredCredentialById(credential.getId());
                    log.info("TOTP credential removed for user: " + userid);
                } catch (Exception e) {
                    log.info("Failed to remove TOTP credential for user: " + userid);
                    return Response.serverError()
                            .entity(Map.of("error", "Failed to disable TOTP", "code", CODE_OPERATION_FAILED))
                            .build();
                }
            }

            return Response.ok(Map.of(
                    "message", "TOTP disabled successfully",
                    "enabled", false,
                    "userId", userid,
                    "code", CODE_SUCCESS
            )).build();
        } catch (Exception e) {
            return handleServerError("disabling TOTP", userid, e);
        }
    }

    public Response disableTotpWithValidation(final String userid, Map<String, String> data) {
        Response validation = validateUserId(userid);
        if (validation != null) return validation;

        try {
            validation = validateTotpCode(data.get("code"));
            if (validation != null) return validation;

            final UserModel user = checkPermissionsAndGetUser(userid);

            // Check if TOTP is enabled
            validation = checkTotpEnabled(user, true);
            if (validation != null) return validation;

            // Validate TOTP code
            final RealmModel realm = session.getContext().getRealm();
            OTPPolicy otpPolicy = realm.getOTPPolicy();
            TimeBasedOTP timeBasedOTP = new TimeBasedOTP(
                    otpPolicy.getAlgorithm(),
                    otpPolicy.getDigits(),
                    otpPolicy.getPeriod(),
                    0
            );

            var totpCredentials = user.credentialManager()
                    .getStoredCredentialsByTypeStream(OTPCredentialModel.TYPE)
                    .toList();
            OTPCredentialModel credential = OTPCredentialModel.createFromCredentialModel(totpCredentials.getFirst());

            if (!timeBasedOTP.validateTOTP(data.get("code"), credential.getDecodedSecret())) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Invalid code", "code", CODE_INVALID_TOTP))
                        .build();
            }

            // Remove all TOTP credentials after validation
            for (var cred : totpCredentials) {
                try {
                    user.credentialManager().removeStoredCredentialById(cred.getId());
                    log.info("TOTP credential removed for user: " + userid);
                } catch (Exception e) {
                    log.error("Failed to remove TOTP credential for user: " + userid, e);
                    return Response.serverError()
                            .entity(Map.of("error", "Failed to disable TOTP", "code", CODE_OPERATION_FAILED))
                            .build();
                }
            }

            return Response.ok(Map.of(
                    "message", "TOTP validated and disabled successfully",
                    "enabled", false,
                    "userId", userid,
                    "code", CODE_SUCCESS
            )).build();

        } catch (Exception e) {
            return handleServerError("disabling TOTP with validation", userid, e);
        }
    }
}
