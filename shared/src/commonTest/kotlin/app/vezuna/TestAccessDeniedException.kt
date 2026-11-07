package app.vezuna

// Models provider access denial without the JVM-only java.lang.SecurityException.
internal class TestAccessDeniedException(message: String) : Exception(message)
