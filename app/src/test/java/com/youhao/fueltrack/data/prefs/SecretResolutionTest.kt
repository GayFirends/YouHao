package com.youhao.fueltrack.data.prefs

import com.google.common.truth.Truth.assertThat
import com.youhao.fueltrack.domain.model.WebDavConfig
import org.junit.Test

/**
 * The rule that decides which WebDAV password a sync authenticates with.
 *
 * This is the security-relevant half of background sync: the password may only come out of the
 * keystore while the user still wants it remembered. It is extracted into a pure function so the
 * rule can be pinned down without an Android `Context`.
 */
class SecretResolutionTest {

    // -----------------------------------------------------------------------
    // WebDAV password
    // -----------------------------------------------------------------------

    @Test
    fun `a password typed this session wins over the stored one`() {
        val resolved = resolveWebDavPassword(
            sessionPassword = "typed-now",
            remember = true,
            storedPassword = "from-keystore",
        )

        assertThat(resolved).isEqualTo("typed-now")
    }

    @Test
    fun `the stored password is used when the session has none and it was remembered`() {
        val resolved = resolveWebDavPassword(
            sessionPassword = "",
            remember = true,
            storedPassword = "from-keystore",
        )

        assertThat(resolved).isEqualTo("from-keystore")
    }

    @Test
    fun `the stored password is ignored once the user stops remembering it`() {
        val resolved = resolveWebDavPassword(
            sessionPassword = "",
            remember = false,
            storedPassword = "from-keystore",
        )

        assertThat(resolved).isEmpty()
    }

    @Test
    fun `nothing is resolved when neither source has a password`() {
        val resolved = resolveWebDavPassword(
            sessionPassword = "",
            remember = true,
            storedPassword = null,
        )

        assertThat(resolved).isEmpty()
    }

    // -----------------------------------------------------------------------
    // Encryption passphrase
    // -----------------------------------------------------------------------

    @Test
    fun `a passphrase typed this session wins over the stored one`() {
        val resolved = resolvePassphrase(
            sessionPassphrase = "typed-now",
            storedPassphrase = "from-keystore",
        )

        assertThat(resolved).isEqualTo("typed-now")
    }

    @Test
    fun `the stored passphrase is the fallback so a background sync can still decrypt`() {
        val resolved = resolvePassphrase(
            sessionPassphrase = "",
            storedPassphrase = "from-keystore",
        )

        assertThat(resolved).isEqualTo("from-keystore")
    }

    @Test
    fun `no passphrase is resolved when neither source has one`() {
        val resolved = resolvePassphrase(
            sessionPassphrase = "",
            storedPassphrase = null,
        )

        assertThat(resolved).isEmpty()
    }

    // -----------------------------------------------------------------------
    // Defaults
    // -----------------------------------------------------------------------

    @Test
    fun `remembering the password defaults to off`() {
        val normalized = config().withDefaults()

        assertThat(normalized.rememberPassword).isFalse()
    }

    @Test
    fun `an explicit choice to remember the password is preserved`() {
        val normalized = config(rememberPassword = true).withDefaults()

        assertThat(normalized.rememberPassword).isTrue()
    }

    @Test
    fun `defaults fill in the sync file name without touching the password`() {
        val normalized = config(fileName = "").withDefaults()

        assertThat(normalized.fileName).isEqualTo(DEFAULT_SYNC_FILE_NAME)
        assertThat(normalized.password).isEqualTo("secret")
    }

    private fun config(
        fileName: String = DEFAULT_SYNC_FILE_NAME,
        rememberPassword: Boolean? = null,
    ) = WebDavConfig(
        url = "https://example.com/dav",
        username = "user",
        password = "secret",
        fileName = fileName,
        rememberPassword = rememberPassword,
    )
}
