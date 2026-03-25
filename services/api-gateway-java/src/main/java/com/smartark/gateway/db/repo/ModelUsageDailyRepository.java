package com.smartark.gateway.db.repo;

import com.smartark.gateway.db.entity.ModelUsageDailyEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ModelUsageDailyRepository extends JpaRepository<ModelUsageDailyEntity, Long> {

    Optional<ModelUsageDailyEntity> findByModelNameAndUsageDate(String modelName, LocalDate usageDate);

    List<ModelUsageDailyEntity> findByUsageDate(LocalDate usageDate);

    List<ModelUsageDailyEntity> findByModelNameAndUsageDateBetweenOrderByUsageDateAsc(
            String modelName, LocalDate start, LocalDate end);

    List<ModelUsageDailyEntity> findByUsageDateBetweenOrderByUsageDateAsc(LocalDate start, LocalDate end);
}
