package com.example.flightops.service;

import com.example.flightops.domain.FlightInstance;
import com.example.flightops.domain.FlightStatus;
import com.example.flightops.repo.FlightInstanceRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 实时口径：按实例当前投影聚合（主键去重，同一航班不会被重复计数）。
 * 分母只含已判定（已起飞）班次；取消班次不计入；未起飞未取消班次待判定。
 */
@Service
public class StatsService {

    private final FlightInstanceRepository instanceRepository;

    public StatsService(FlightInstanceRepository instanceRepository) {
        this.instanceRepository = instanceRepository;
    }

    public Map<String, Object> realtime(LocalDate date) {
        List<FlightInstance> instances = instanceRepository.findByIdServiceDateOrderByIdFlightNoAscIdLegNoAsc(date);
        int judged = 0;
        int normal = 0;
        int cancelled = 0;
        int pending = 0;
        for (FlightInstance instance : instances) {
            if (instance.getReleaseNormal() != null) {
                judged++;
                if (Boolean.TRUE.equals(instance.getReleaseNormal())) {
                    normal++;
                }
            } else if (instance.getCurrentStatus() == FlightStatus.CANCELLED) {
                cancelled++;
            } else {
                pending++;
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("date", date.toString());
        result.put("caliber", "REALTIME");
        result.put("totalInstances", instances.size());
        result.put("counted", judged);
        result.put("normal", normal);
        result.put("cancelledExcluded", cancelled);
        result.put("pendingJudgment", pending);
        result.put("rate", rate(normal, judged));
        result.put("note", "实时口径：分母=已判定（已起飞）班次；取消不计入；未起飞班次待判定");
        return result;
    }

    static BigDecimal rate(int normal, int counted) {
        if (counted == 0) {
            return null;
        }
        return BigDecimal.valueOf(100.0 * normal / counted).setScale(2, RoundingMode.HALF_UP);
    }
}
