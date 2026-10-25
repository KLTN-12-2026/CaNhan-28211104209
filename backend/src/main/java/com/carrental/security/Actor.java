package com.carrental.security;
import java.security.Principal;
public record Actor(String id, String role, String fullName) implements Principal {
 public String getName(){return id;}
 public boolean is(String expected){return role.equals(expected);}
 public void require(String expected){if(!is(expected))throw com.carrental.exception.DomainException.forbidden();}
}
