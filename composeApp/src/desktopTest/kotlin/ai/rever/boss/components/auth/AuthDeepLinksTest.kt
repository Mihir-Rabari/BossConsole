package ai.rever.boss.components.auth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Pins the structural contract of the auth deep-link segment.
 *
 * `boss://` is registered with the OS, so any web page can open one of these links, and the
 * auth segment drives session-establishing actions (email verification, authentication
 * completed) with no confirmation. [AuthDeepLinks.parse] therefore refuses every link that is
 * not exactly a known producer's shape before anything acts on it. Each `assertNull` below is
 * a refusal the old substring matching let through: a `passkey/authenticated` smuggled inside
 * a parameter value, a lookalike host, a session id scraped out of the fragment, an ambiguous
 * duplicate parameter. A regression on any of these reopens a login-CSRF-shaped door that any
 * web page can walk a user's OS into.
 */
class AuthDeepLinksTest {
    private val sessionId = "123e4567-e89b-12d3-a456-426614174000"
    private val accessToken = "eyJhbGciOiJIUzI1NiJ9.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"
    private val supabaseFragmentLink =
        "boss://auth/verify#access_token=$accessToken&refresh_token=r1&expires_in=3600&token_type=bearer"

    @Test
    fun `parse reads a Supabase fragment magic link and tolerates its extra fragment params`() {
        val link = AuthDeepLinks.parse(supabaseFragmentLink)
        assertIs<AuthDeepLink.MagicLinkVerify>(link)
        assertEquals(accessToken, link.token)
        assertEquals("magiclink", link.type)
    }

    @Test
    fun `parse reads a query-token magic link with its type`() {
        val link = AuthDeepLinks.parse("boss://auth/verify?token=t&type=signup")
        assertIs<AuthDeepLink.MagicLinkVerify>(link)
        assertEquals("t", link.token)
        assertEquals("signup", link.type)
    }

    @Test
    fun `parse defaults the magic-link type when neither section carries one`() {
        val link = AuthDeepLinks.parse("boss://auth/verify?token=t")
        assertIs<AuthDeepLink.MagicLinkVerify>(link)
        assertEquals("magiclink", link.type)
    }

    @Test
    fun `parse prefers a fragment type over the default`() {
        val link = AuthDeepLinks.parse("boss://auth/verify#access_token=x&type=recovery")
        assertIs<AuthDeepLink.MagicLinkVerify>(link)
        assertEquals("recovery", link.type)
    }

    @Test
    fun `parse reads a passkey registered callback`() {
        val link = AuthDeepLinks.parse("boss://passkey/registered?sessionId=$sessionId")
        assertIs<AuthDeepLink.PasskeyRegistered>(link)
        assertEquals(sessionId, link.sessionId)
    }

    @Test
    fun `parse reads a passkey authenticated callback`() {
        val link = AuthDeepLinks.parse("boss://passkey/authenticated?sessionId=$sessionId")
        assertIs<AuthDeepLink.PasskeyAuthenticated>(link)
        assertEquals(sessionId, link.sessionId)
    }

    @Test
    fun `parse accepts an uppercase scheme because URI schemes are case-insensitive`() {
        val link = AuthDeepLinks.parse("BOSS://auth/verify?token=t&type=signup")
        assertIs<AuthDeepLink.MagicLinkVerify>(link)
        assertEquals("t", link.token)
    }

    @Test
    fun `parse accepts a token at the length bound`() {
        val atBound = "a".repeat(2048)
        val link = AuthDeepLinks.parse("boss://auth/verify?token=$atBound")
        assertIs<AuthDeepLink.MagicLinkVerify>(link)
        assertEquals(atBound, link.token)
    }

    @Test
    fun `parse refuses a passkey route smuggled inside a parameter value`() {
        assertNull(AuthDeepLinks.parse("boss://url?target=passkey/authenticated?sessionId=$sessionId"))
    }

    @Test
    fun `parse refuses an auth route smuggled inside a parameter value`() {
        assertNull(AuthDeepLinks.parse("boss://file/open?path=/tmp/auth/verify&token=x"))
    }

    @Test
    fun `parse refuses the passkey route on a hostile host`() {
        assertNull(AuthDeepLinks.parse("boss://evil/passkey/authenticated?sessionId=$sessionId"))
    }

    @Test
    fun `parse refuses a host that only prefixes a real one`() {
        assertNull(AuthDeepLinks.parse("boss://passkey-evil/registered?sessionId=$sessionId"))
    }

    @Test
    fun `parse refuses a session id that is not a UUID`() {
        assertNull(AuthDeepLinks.parse("boss://passkey/registered?sessionId=not-a-uuid"))
    }

    @Test
    fun `parse refuses a session id carried in the fragment instead of the query`() {
        assertNull(AuthDeepLinks.parse("boss://passkey/registered#sessionId=$sessionId"))
        assertNull(AuthDeepLinks.parse("boss://passkey/registered?sessionId=$sessionId#sessionId=$sessionId"))
    }

    @Test
    fun `parse refuses a duplicated session id`() {
        assertNull(AuthDeepLinks.parse("boss://passkey/registered?sessionId=$sessionId&sessionId=$sessionId"))
    }

    @Test
    fun `parse refuses a passkey callback without a session id`() {
        assertNull(AuthDeepLinks.parse("boss://passkey/registered"))
        assertNull(AuthDeepLinks.parse("boss://passkey/authenticated?email=attacker@evil.example"))
    }

    @Test
    fun `parse refuses an unknown path under a known host`() {
        assertNull(AuthDeepLinks.parse("boss://passkey/enroll?sessionId=$sessionId"))
        assertNull(AuthDeepLinks.parse("boss://auth/reset?token=t"))
    }

    @Test
    fun `parse refuses a non-boss scheme`() {
        assertNull(AuthDeepLinks.parse("https://auth/verify?token=t&type=signup"))
        assertNull(AuthDeepLinks.parse("evilboss://passkey/registered?sessionId=$sessionId"))
    }

    @Test
    fun `parse refuses a token with characters no producer URL ever carries`() {
        assertNull(AuthDeepLinks.parse("boss://auth/verify?token=abc+def&type=signup"))
        assertNull(AuthDeepLinks.parse("boss://auth/verify#access_token=a%20b"))
    }

    @Test
    fun `parse refuses a magic-link host with no token at all`() {
        assertNull(AuthDeepLinks.parse("boss://auth/verify"))
        assertNull(AuthDeepLinks.parse("boss://auth/verify?email=attacker@evil.example"))
    }

    @Test
    fun `parse refuses a duplicated token`() {
        assertNull(AuthDeepLinks.parse("boss://auth/verify?token=a&token=b"))
        assertNull(AuthDeepLinks.parse("boss://auth/verify#access_token=a&access_token=b"))
    }

    @Test
    fun `parse refuses a token beyond the length bound`() {
        assertNull(AuthDeepLinks.parse("boss://auth/verify?token=${"a".repeat(2049)}"))
    }

    @Test
    fun `parse refuses a case-tweaked host because the allowlist is case-sensitive`() {
        assertNull(AuthDeepLinks.parse("boss://AUTH/verify?token=t&type=signup"))
        assertNull(AuthDeepLinks.parse("boss://Passkey/registered?sessionId=$sessionId"))
    }
}
