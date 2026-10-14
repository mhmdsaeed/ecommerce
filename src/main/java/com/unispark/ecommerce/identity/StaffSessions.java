package com.unispark.ecommerce.identity;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** Starts a staff session without the login form, e.g. right after signup. */
public interface StaffSessions {

    void logIn(StaffPrincipal staff, HttpServletRequest request, HttpServletResponse response);
}
