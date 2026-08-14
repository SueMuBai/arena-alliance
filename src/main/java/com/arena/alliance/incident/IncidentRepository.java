package com.arena.alliance.incident;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IncidentRepository extends JpaRepository<Incident, Long> {

    List<Incident> findTop200ByOrderByIdDesc();

    List<Incident> findTop50ByOrderByIdDesc();
}
