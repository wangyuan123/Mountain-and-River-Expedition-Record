package com.wargame.repository;

import com.wargame.model.entity.SharedIncomingReport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SharedIncomingReportRepository extends JpaRepository<SharedIncomingReport, String> {}
