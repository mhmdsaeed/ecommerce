package com.unispark.ecommerce;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface IsolationProbeRepository extends JpaRepository<IsolationProbe, UUID> {

    List<IsolationProbe> findByLabel(String label);

    @Query("select p from IsolationProbe p order by p.label")
    List<IsolationProbe> findAllByJpql();
}
