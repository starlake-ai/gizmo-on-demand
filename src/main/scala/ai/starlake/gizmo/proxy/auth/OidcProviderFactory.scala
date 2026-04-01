package ai.starlake.gizmo.proxy.auth

import ai.starlake.gizmo.proxy.config.*

/** Constructs OidcBearerAuthenticator instances with the correct JWKS URL
  * and expected issuer/audience for each supported OIDC provider.
  */
object OidcProviderFactory:

  def createKeycloak(config: KeycloakAuthConfig, roleClaim: String): OidcBearerAuthenticator =
    val jwksUrl = s"${config.baseUrl}/realms/${config.realm}/protocol/openid-connect/certs"
    val issuer = s"${config.baseUrl}/realms/${config.realm}"
    new OidcBearerAuthenticator("keycloak", jwksUrl, issuer, config.clientId, roleClaim)

  def createGoogle(config: GoogleAuthConfig, roleClaim: String): OidcBearerAuthenticator =
    new OidcBearerAuthenticator(
      "google",
      "https://www.googleapis.com/oauth2/v3/certs",
      "https://accounts.google.com",
      config.clientId,
      roleClaim
    )

  def createAzure(config: AzureAuthConfig, roleClaim: String): OidcBearerAuthenticator =
    val jwksUrl =
      s"https://login.microsoftonline.com/${config.tenantId}/discovery/v2.0/keys"
    val issuer =
      s"https://login.microsoftonline.com/${config.tenantId}/v2.0"
    new OidcBearerAuthenticator("azure", jwksUrl, issuer, config.clientId, roleClaim)

  def createAws(config: AwsAuthConfig, roleClaim: String): OidcBearerAuthenticator =
    val base = s"https://cognito-idp.${config.region}.amazonaws.com/${config.userPoolId}"
    val jwksUrl = s"$base/.well-known/jwks.json"
    new OidcBearerAuthenticator("aws-cognito", jwksUrl, base, config.clientId, roleClaim)