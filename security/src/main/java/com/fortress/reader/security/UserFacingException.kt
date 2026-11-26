package com.fortress.reader.security

/** An error whose message is safe and useful to show to the user (never contains file contents). */
class UserFacingException(message: String, cause: Throwable? = null) : Exception(message, cause)
