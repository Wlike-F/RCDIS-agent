package com.rcdis.agent.agent.tools;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcdis.agent.common.util.DateTimeUtils;
import com.rcdis.agent.common.util.MoneyUtils;

import lombok.RequiredArgsConstructor;

/**
 * Deterministic, side-effect-free foundation tools.
 *
 * <p>The model is unreliable at calendar arithmetic and decimal addition, and it has no trustworthy
 * clock. These tools push exactly those computations into code so downstream tools always receive a
 * strict {@code YYYY-MM-DD} date and exact money totals. Everything here is read-only and never
 * requires confirmation.</p>
 */
@Component
@RequiredArgsConstructor
public class BasicTools {

    private static final String TOOL_NOW = "get_current_datetime";
    private static final String TOOL_PARSE_DATE = "parse_date";
    private static final String TOOL_DATE_RANGE = "date_range";
    private static final String TOOL_SUM_AMOUNTS = "sum_amounts";
    private static final String TOOL_BUDGET_REMAINING = "budget_remaining";

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final Pattern OFFSET_DAYS = Pattern.compile("^(\\d+)\\s*(天前|天后)$");
    private static final Pattern COMPACT = Pattern.compile("^(\\d{4})(\\d{2})(\\d{2})$");
    private static final Pattern SLASHED = Pattern.compile("^(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})$");
    private static final Pattern CHINESE = Pattern.compile("^(\\d{4})年(\\d{1,2})月(\\d{1,2})日$");
    private static final Pattern RECENT_DAYS = Pattern.compile("^最近\\s*(\\d+)\\s*天$");

    private final ObjectMapper objectMapper;

    @Tool(name = TOOL_NOW,
            description = "获取服务器当前日期与时间（Asia/Shanghai）。模型自身没有可靠时钟，涉及『今天/本月/截止』等判断时必须先调用本工具。"
                    + "返回 isoDate(YYYY-MM-DD)、isoDateTime、weekday(中文星期)、daysInCurrentMonth、timezone。")
    public String getCurrentDatetime() {
        LocalDate today = DateTimeUtils.today(java.time.Clock.systemDefaultZone(), DateTimeUtils.DEFAULT_ZONE_ID);
        OffsetDateTime now = DateTimeUtils.now(java.time.Clock.systemDefaultZone(), DateTimeUtils.DEFAULT_ZONE_ID);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("isoDate", today.format(ISO));
        result.put("isoDateTime", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
        result.put("weekday", weekdayChinese(today.getDayOfWeek()));
        result.put("daysInCurrentMonth", YearMonth.from(today).lengthOfMonth());
        result.put("timezone", DateTimeUtils.DEFAULT_ZONE_ID.getId());
        return json(result);
    }

    @Tool(name = TOOL_PARSE_DATE,
            description = "把用户给出的松散日期归一化为 ISO 格式 YYYY-MM-DD。支持：2026-09-15 / 2026/9/15 / 2026.9.15 / "
                    + "20260915 / 2026年9月15日 / 今天 / 昨天 / 前天 / 明天 / 后天 / N天前 / N天后 / 本月初 / 本月末 / 上月初 / 上月末。"
                    + "返回 iso、weekday、daysFromToday(负数表示过去)。供其他需要严格日期的工具使用前调用。")
    public String parseDate(@ToolParam(description = "待解析的日期文本") String text) {
        if (!StringUtils.hasText(text)) {
            return json(Map.of("ok", false, "error", "请提供日期文本"));
        }
        LocalDate today = DateTimeUtils.today(java.time.Clock.systemDefaultZone(), DateTimeUtils.DEFAULT_ZONE_ID);
        try {
            LocalDate date = resolveDate(text.trim(), today);
            if (date == null) {
                return json(Map.of("ok", false, "error", "无法识别的日期格式：" + text));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("input", text);
            result.put("iso", date.format(ISO));
            result.put("weekday", weekdayChinese(date.getDayOfWeek()));
            result.put("daysFromToday", java.time.temporal.ChronoUnit.DAYS.between(today, date));
            return json(result);
        } catch (RuntimeException exception) {
            return json(Map.of("ok", false, "error", "日期解析失败：" + exception.getMessage()));
        }
    }

    @Tool(name = TOOL_DATE_RANGE,
            description = "计算一个日期闭区间 [startIso, endIso]。二选一：传 period 周期关键字（今天/昨天/本周/上周/本月/上月/"
                    + "本年/去年/最近N天），或传 start 与 end（可被 parse_date 识别的格式）。返回 startIso、endIso(含当天)、days。"
                    + "用于按日期过滤支出/报销等查询前的区间换算。")
    public String dateRange(
            @ToolParam(description = "周期关键字：今天/昨天/本周/上周/本月/上月/本年/去年/最近N天", required = false) String period,
            @ToolParam(description = "区间开始日期，与 period 二选一", required = false) String start,
            @ToolParam(description = "区间结束日期，与 period 二选一", required = false) String end) {
        LocalDate today = DateTimeUtils.today(java.time.Clock.systemDefaultZone(), DateTimeUtils.DEFAULT_ZONE_ID);
        try {
            LocalDate from;
            LocalDate to;
            if (StringUtils.hasText(period)) {
                LocalDate[] range = resolvePeriod(period.trim(), today);
                if (range == null) {
                    return json(Map.of("ok", false, "error", "无法识别的周期：" + period));
                }
                from = range[0];
                to = range[1];
            } else if (StringUtils.hasText(start) && StringUtils.hasText(end)) {
                from = resolveDate(start.trim(), today);
                to = resolveDate(end.trim(), today);
                if (from == null || to == null) {
                    return json(Map.of("ok", false, "error", "无法识别的开始或结束日期"));
                }
            } else {
                return json(Map.of("ok", false, "error", "请提供 period，或同时提供 start 与 end"));
            }
            if (from.isAfter(to)) {
                return json(Map.of("ok", false, "error", "开始日期晚于结束日期"));
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("ok", true);
            result.put("startIso", from.format(ISO));
            result.put("endIso", to.format(ISO));
            result.put("days", java.time.temporal.ChronoUnit.DAYS.between(from, to) + 1);
            return json(result);
        } catch (RuntimeException exception) {
            return json(Map.of("ok", false, "error", "区间计算失败：" + exception.getMessage()));
        }
    }

    @Tool(name = TOOL_SUM_AMOUNTS,
            description = "对一组金额做精确求和与平均（BigDecimal，保留 2 位小数，四舍五入）。模型不要自行心算金额合计，一律调用本工具。"
                    + "入参 amounts 为数字列表。返回 count、total、average。")
    public String sumAmounts(@ToolParam(description = "金额列表") List<BigDecimal> amounts) {
        if (amounts == null || amounts.isEmpty()) {
            return json(Map.of("ok", false, "error", "请提供至少一个金额"));
        }
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal amount : amounts) {
            total = MoneyUtils.add(total, amount);
        }
        BigDecimal average = total.divide(BigDecimal.valueOf(amounts.size()), MoneyUtils.MONEY_SCALE,
                RoundingMode.HALF_UP);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("count", amounts.size());
        result.put("total", MoneyUtils.normalize(total));
        result.put("average", average);
        return json(result);
    }

    @Tool(name = TOOL_BUDGET_REMAINING,
            description = "计算预算科目可用额与执行率：remaining = allocated - used - frozen；executionRatePercent = used / allocated * 100。"
                    + "模型不要自行做减法/除法，一律调用本工具。返回 remaining、executionRatePercent。")
    public String budgetRemaining(
            @ToolParam(description = "分配额 allocated") BigDecimal allocated,
            @ToolParam(description = "已用额 used") BigDecimal used,
            @ToolParam(description = "冻结额 frozen") BigDecimal frozen) {
        if (allocated == null || used == null || frozen == null) {
            return json(Map.of("ok", false, "error", "请提供 allocated、used、frozen 三个金额"));
        }
        BigDecimal remaining = MoneyUtils.subtract(MoneyUtils.subtract(allocated, used), frozen);
        BigDecimal rate = null;
        if (allocated.signum() != 0) {
            rate = used.multiply(BigDecimal.valueOf(100))
                    .divide(allocated, MoneyUtils.MONEY_SCALE, RoundingMode.HALF_UP);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("remaining", remaining);
        result.put("executionRatePercent", rate);
        return json(result);
    }

    // ---------- date resolution ----------

    private LocalDate resolveDate(String text, LocalDate today) {
        switch (text) {
            case "今天", "今日" -> {
                return today;
            }
            case "昨天", "昨日" -> {
                return today.minusDays(1);
            }
            case "前天" -> {
                return today.minusDays(2);
            }
            case "明天", "明日" -> {
                return today.plusDays(1);
            }
            case "后天" -> {
                return today.plusDays(2);
            }
            case "本月初" -> {
                return YearMonth.from(today).atDay(1);
            }
            case "本月末" -> {
                return YearMonth.from(today).atEndOfMonth();
            }
            case "上月初" -> {
                return YearMonth.from(today).minusMonths(1).atDay(1);
            }
            case "上月末" -> {
                return YearMonth.from(today).minusMonths(1).atEndOfMonth();
            }
            default -> {
                // fall through to pattern matching below
            }
        }
        Matcher offset = OFFSET_DAYS.matcher(text);
        if (offset.matches()) {
            long n = Long.parseLong(offset.group(1));
            return "天前".equals(offset.group(2)) ? today.minusDays(n) : today.plusDays(n);
        }
        Matcher compact = COMPACT.matcher(text);
        if (compact.matches()) {
            return LocalDate.of(intOf(compact, 1), intOf(compact, 2), intOf(compact, 3));
        }
        Matcher slashed = SLASHED.matcher(text);
        if (slashed.matches()) {
            return LocalDate.of(intOf(slashed, 1), intOf(slashed, 2), intOf(slashed, 3));
        }
        Matcher chinese = CHINESE.matcher(text);
        if (chinese.matches()) {
            return LocalDate.of(intOf(chinese, 1), intOf(chinese, 2), intOf(chinese, 3));
        }
        try {
            return LocalDate.parse(text, ISO);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private LocalDate[] resolvePeriod(String period, LocalDate today) {
        Matcher recent = RECENT_DAYS.matcher(period);
        if (recent.matches()) {
            long n = Long.parseLong(recent.group(1));
            return new LocalDate[] {today.minusDays(n - 1), today};
        }
        return switch (period) {
            case "今天", "今日" -> new LocalDate[] {today, today};
            case "昨天", "昨日" -> new LocalDate[] {today.minusDays(1), today.minusDays(1)};
            case "本周" -> {
                LocalDate monday = mondayOf(today);
                yield new LocalDate[] {monday, monday.plusDays(6)};
            }
            case "上周" -> {
                LocalDate thisMonday = mondayOf(today);
                LocalDate lastMonday = thisMonday.minusWeeks(1);
                yield new LocalDate[] {lastMonday, lastMonday.plusDays(6)};
            }
            case "本月" -> new LocalDate[] {YearMonth.from(today).atDay(1), YearMonth.from(today).atEndOfMonth()};
            case "上月" -> {
                YearMonth last = YearMonth.from(today).minusMonths(1);
                yield new LocalDate[] {last.atDay(1), last.atEndOfMonth()};
            }
            case "本年", "今年" -> new LocalDate[] {LocalDate.of(today.getYear(), 1, 1), LocalDate.of(today.getYear(), 12, 31)};
            case "去年", "上年" -> new LocalDate[] {LocalDate.of(today.getYear() - 1, 1, 1), LocalDate.of(today.getYear() - 1, 12, 31)};
            default -> null;
        };
    }

    private LocalDate mondayOf(LocalDate date) {
        return date.minusDays(date.getDayOfWeek().getValue() - 1L);
    }

    private static int intOf(Matcher matcher, int group) {
        return Integer.parseInt(matcher.group(group));
    }

    private static String weekdayChinese(DayOfWeek dayOfWeek) {
        return switch (dayOfWeek) {
            case MONDAY -> "星期一";
            case TUESDAY -> "星期二";
            case WEDNESDAY -> "星期三";
            case THURSDAY -> "星期四";
            case FRIDAY -> "星期五";
            case SATURDAY -> "星期六";
            case SUNDAY -> "星期日";
        };
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            return "{\"ok\":false,\"error\":\"序列化工具结果失败\"}";
        }
    }
}
