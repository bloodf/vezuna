package app.vezuna

/**
 * Backup produced by an independent implementation (Python hashlib.pbkdf2_hmac + cryptography AESGCM) with
 * salt 00..0f, nonce a0..ab, 600,000 iterations. Every platform actual must open it.
 */
internal object GoldenBackup {
    /** NFC form; the NFD form of the same text must open it too. */
    const val PASSPHRASE_NFC = "p\u00e4ssw\u00f6rd correct horse"
    const val PASSPHRASE_NFD = "pa\u0308sswo\u0308rd correct horse"
    const val KEY_HEX = "670607ccb1c1ff5d15779935001a9f594d11ae42ac4edf25c969cebe835dd428"
    const val FILE_BASE64 = "VlpCSwEBAAknwBAAAQIDBAUGBwgJCgsMDQ4PDKChoqOkpaanqKmqqwjkjnhrpA8RLx9TKTMNw8PQY7w+8Ldn5Qd8la/2CrEk3O4sdjDpOKpUCmJe8G9x12dKuOY4RshQ2uOa6AR7yhZmNnrKqgDxo6e8dUn2SjMwxtCOAnziTp3CGXPuEmnMiTP7pdYwaWMR6jkYX5jJqcZoXCX7wPiCtmhLrI41V/F1gP69lkMMssxc26MboTDb4diQE6jrNiNqJw6wNrnCC+PuUhtOyMPloIubISU4w/C20UlRzSBGJVun2COqzDF53F2I4mC0EzzbsndQOOfbpwA="
    const val PROFILE_ID = "golden-profile"
    const val PBKDF2_NON_ASCII_4096_HEX = "7aee4b7ca50d9b45480cd57eedd2f4b9fb2ba09cb7a05a592f7f8b56503efb90"
}
