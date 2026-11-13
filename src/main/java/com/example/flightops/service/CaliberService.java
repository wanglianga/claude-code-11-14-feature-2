package com.example.flightops.service;

import com.example.flightops.config.AppProperties;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计口径（写死，任何改动必须改代码并升版本）：同一批数据之所以算出三个正常率，
 * 是因为口径没写死。以下规则为本服务唯一口径。
 */
@Service
public class CaliberService {

    private final AppProperties properties;

    public CaliberService(AppProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> caliber() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", "1.0");
        result.put("instanceKey", "航班实例 = (航班号, 执行日期, 航段号)；补班/加班/同日同号不同航段各自成独立实例");
        result.put("rules", List.of(
                "R1 取消航班：最终状态为取消且未恢复执行的，不计入分母",
                "R2 备降后改飞/续飞：算一班（同一实例），放行判定按首次实际起飞时刻",
                "R3 临时取消又恢复：记在执行日期当天（同一实例内取消与恢复冲抵），按最终执行结果判定",
                "R4 正常放行：实际起飞时刻 <= 判定时生效的计划起飞时刻 + " + properties.releaseToleranceMinutesOrDefault() + " 分钟",
                "R5 延误分钟 = max(0, 实际起飞 - 判定绑定计划起飞)，判定绑定判定时生效的计划版本，事后改计划不回写",
                "R6 应飞未飞（既未起飞也未取消）：日报口径计入分母且记为不正常；实时口径暂不计入分母（待判定）",
                "R7 迟到窗口 " + properties.lateWindowHoursOrDefault() + " 小时：窗口内事件触发结果更新与撤回（冲抵）；超窗事件走补录并标注 BACKFILL 来源",
                "R8 日报每天用同一批事件重算：逐航班比对放行状态与延误分钟，正常性翻转或延误差超过 "
                        + properties.diffThresholdMinutesOrDefault() + " 分钟记为差异并定位到具体变更",
                "R9 日报锁定后更正另出一份（CORRECTION 版本），原值、更正值与原因同时可查，原版本永不改写"
        ));
        result.put("realtimeCaliber", "分母 = 已判定（已起飞）班次；取消不计入；未起飞班次待判定");
        result.put("dailyCaliber", "分母 = 已起飞班次 + 应飞未飞班次（记不正常）；取消不计入");
        return result;
    }
}
