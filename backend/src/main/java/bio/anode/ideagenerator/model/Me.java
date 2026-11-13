package bio.anode.ideagenerator.model;

/** Who is signed in (name and email as the login provider gave them). */
public record Me(String name, String email) {}
