package com.wargame.repository;

import com.wargame.model.entity.SharedReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SharedReportRepository extends JpaRepository<SharedReport, String> {}
