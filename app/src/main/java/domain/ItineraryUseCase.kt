package com.example.project_2.domain

import android.util.Log
import com.example.project_2.domain.model.*
import com.example.project_2.data.openai.OpenAiService
import org.json.JSONObject
import java.time.LocalTime

/**
 * 하루 기본 시간/마지막 날 종료 시간 설정용 Config
 * - defaultStart: Day1 시작 기준 시간 (예: 현재 시간)
 * - defaultEnd: 기본 하루 종료 시간
 * - lastDayEndOverride: 마지막 날 기차/버스 때문에 더 일찍 끝내고 싶을 때 사용
 */
data class ItineraryConfig(
    val defaultStart: String = "10:00",
    val defaultEnd: String = "21:30",
    val lastDayEndOverride: String? = null
)

/**
 * 일정 생성 UseCase
 * - GPT를 사용해서 장소들을 Day별로 배치하고 시간 배정
 */
class ItineraryUseCase(
    private val openAi: OpenAiPort = OpenAiService
) {
    private val TAG = "ItineraryUseCase"

    /**
     * 일정 생성 (간단 버전)
     * @param selectedPlaces 사용자가 선택한 장소 리스트
     * @param filter 사용자 필터 (기간, 인원, 필수 장소 등)
     * @param autoAddMeals 식사 시간 자동 추가 여부
     * @param config 하루 기본 시간 / 마지막 날 종료 시간 설정
     * @return 생성된 일정
     *
     * ✅ Day1 의 실제 시작 시간은 "현재 시간" 기준으로 자동 조정됨
     *    (단, 지금 시간이 10시 이전이면 10:00부터 시작)
     */
    suspend fun generateItinerary(
        selectedPlaces: List<Place>,
        filter: FilterState,
        autoAddMeals: Boolean = false,
        config: ItineraryConfig = ItineraryConfig()
    ): Itinerary {
        val days = filter.duration.toDays()

        // 🔥 여기서 "현재 시간" 기반으로 effectiveConfig 생성
        val minStart = LocalTime.of(10, 0)
        val now = LocalTime.now().withSecond(0).withNano(0)
        val effectiveStartTime = if (now.isBefore(minStart)) minStart else now
        val effectiveStartStr = effectiveStartTime.toString().substring(0, 5) // "HH:mm"

        val effectiveConfig = config.copy(defaultStart = effectiveStartStr)

        Log.d(
            TAG,
            "generateItinerary: ${selectedPlaces.size} places, ${days} days, autoAddMeals=$autoAddMeals, config=$effectiveConfig"
        )

        // GPT에게 일정 생성 요청
        val prompt = buildItineraryPrompt(selectedPlaces, filter, days, effectiveConfig)
        Log.d(TAG, "GPT Prompt:\n$prompt")

        val gptResponse = try {
            openAi.completeJson(prompt)
        } catch (e: Exception) {
            Log.e(TAG, "GPT call failed, using fallback", e)
            // GPT 실패 시 간단한 fallback 로직
            return generateFallbackItinerary(selectedPlaces, days, autoAddMeals, effectiveConfig)
        }

        Log.d(TAG, "GPT Response:\n$gptResponse")

        // GPT 응답 파싱
        val daySchedules = parseGptResponse(gptResponse, selectedPlaces, days, autoAddMeals)

        // ✅ Day1 시작 시간을 effectiveConfig.defaultStart(=현재 시간 또는 10시) 기준으로 재계산
        val adjusted = shiftDay1ToConfigStart(daySchedules, effectiveConfig)

        return Itinerary(days = adjusted)
    }

    private fun buildItineraryPrompt(
        places: List<Place>,
        filter: FilterState,
        days: Int,
        config: ItineraryConfig
    ): String {
        val placesText = places.mapIndexed { idx, p ->
            "- id=$idx, name=${p.name}, category=${p.category}, lat=${p.lat}, lng=${p.lng}"
        }.joinToString("\n")

        val mandatoryText = if (filter.mandatoryPlace.isNotBlank()) {
            "\n- 필수 방문: ${filter.mandatoryPlace} (반드시 포함)"
        } else ""

        val defaultStart = config.defaultStart
        val defaultEnd = config.defaultEnd
        val lastDayEnd = config.lastDayEndOverride

        val lastDayConstraint = if (lastDayEnd != null) {
            """
[마지막 날 제약]
- 마지막 날(Day $days)은 귀가를 위해 **$lastDayEnd 이전에 모든 일정을 마쳐야 합니다.**
- Day $days 의 마지막 활동 종료 시간이 $lastDayEnd 를 넘지 않도록 start_time과 duration_min을 조정하세요.
""".trimIndent()
        } else {
            ""
        }

        return """
당신은 여행 일정 최적화 전문 AI입니다.
선택된 ${places.size}개 장소를 ${days}일 일정에 **최대한 많이** 포함하되, 여행자의 피로도와 동선을 고려하여 스마트하게 배치해주세요.

[선택된 장소]
$placesText

[조건]
- 총 ${days}일 일정
- 인원: ${filter.numberOfPeople}명$mandatoryText

[핵심 목표]
1. **가능한 많은 장소를 포함** (${places.size}개 중 최소 80% 이상 포함 목표)
2. 각 장소의 거리와 중요도를 고려하여 **체류 시간과 이동 시간을 동적으로 조정**
3. 시간대별 특성에 맞는 장소 배치
4. 현실적인 출발 시간을 반영해서, 아침 일찍(08시대) 시작하지 말 것

[시간대별 활동 가이드 (현실적인 시간대)]
- 기본 하루 사용 가능 시간: **$defaultStart ~ $defaultEnd**
- Day 1 은 이동 시간을 고려하여, **첫 일정 시작 시간을 $defaultStart~13:00 사이**로 설정
- 10:00-12:00 (오전): 관광지, 사진 명소, 문화 시설, 체험 활동
- 12:00-13:00 (점심): FOOD 카테고리 장소 또는 "MEAL" 활동
- 13:00-18:00 (오후): 관광지, 카페, 쇼핑, 힐링 장소
- 18:00-19:00 (저녁): FOOD 카테고리 장소 또는 "MEAL" 활동
- 19:00-21:30 (야간): 나이트 명소, 야경, 카페

[스마트 시간 배분 규칙]
1. **체류 시간을 유연하게 조정** (카테고리별 권장 시간은 참고만 하고 실제로는 동적으로 조정):
   - FOOD: 60-90분 (간단한 식사는 60분, 여유있는 식사는 90분)
   - CAFE: 30-60분 (휴식 겸 방문은 30분, 여유있게는 60분)
   - PHOTO: 45-75분 (사진만 찍는 곳은 45분, 둘러볼 곳 많으면 75분)
   - CULTURE, EXPERIENCE: 60-120분 (규모에 따라 조정)
   - HEALING, SHOPPING: 45-90분
   - NIGHT: 45-90분

2. **이동 시간 최적화**:
   - 가까운 장소(같은 지역): 5-10분
   - 중간 거리: 15-20분
   - 먼 거리: 25-30분
   - 위도/경도 차이로 거리 추정하여 배정

3. **더 많은 장소 포함을 위한 전략**:
   - 가까운 장소들은 체류 시간을 짧게 조정
   - 이동 동선을 최적화하여 이동 시간 최소화
   - 하루에 8-12개 장소 포함 목표 (식사 포함)
   - 필요시 Day 2 이후 일정도 $defaultStart~$defaultEnd 사이에서 효율적으로 채우기

4. **식사 시간 배치**:
   - 점심: 12:00-13:00 (60-90분)
   - 저녁: 18:00-19:00 (60-90분)
   - FOOD 카테고리 장소가 있으면 해당 시간대에 배치
   - **중요**: FOOD 장소가 없으면 식사 시간을 비워두세요 (다른 활동으로 채우기)

5. **Day별 균등 배치**:
   - ${days}일이면 각 날마다 약 ${(places.size.toDouble() / days).toInt()}-${(places.size.toDouble() / days + 2).toInt()}개 장소 배치
   - 거리와 동선을 고려하여 같은 지역 장소들을 같은 날에 배치

[제약 조건]
- 같은 place_id는 전체 일정(days 배열 전체)에서 **최대 1번만 사용**하세요.
- 즉, 한 장소는 Day 1~Day $days 중 딱 한 번만 VISIT 합니다.

[시간/출력 규칙]
- "start_time" 은 항상 "HH:mm" 형식 (예: "10:00", "13:45")
- 하루의 첫 "start_time" 은 **최소 "$defaultStart" 이상**이어야 합니다.
- 마지막 활동의 종료 시간은 **$defaultEnd** 을 넘지 않도록 duration을 조정하세요.
$lastDayConstraint

출력 형식 (JSON):
{
  "days": [
    {
      "day": 1,
      "slots": [
        {"place_id": 0, "start_time": "$defaultStart", "duration_min": 60, "activity": "VISIT"},
        {"place_id": 1, "start_time": "11:10", "duration_min": 75, "activity": "VISIT"},
        {"place_id": 2, "start_time": "12:30", "duration_min": 45, "activity": "VISIT"},
        {"place_id": 3, "start_time": "13:30", "duration_min": 60, "activity": "VISIT"}
      ]
    }
  ]
}

**중요**: 가능한 많은 장소를 포함하되, 시간 배분은 위 가이드를 참고하여 각 장소에 맞게 동적으로 조정하세요.
""".trimIndent()
    }

    private fun parseGptResponse(
        gptResponse: String,
        places: List<Place>,
        days: Int,
        autoAddMeals: Boolean
    ): List<DaySchedule> {
        return try {
            val json = sanitizeJson(gptResponse)
            val root = JSONObject(json)
            val daysArray = root.getJSONArray("days")

            val schedules = mutableListOf<DaySchedule>()

            // ✅ 전체 일정에서 중복 방문 방지용
            val usedPlaceIds = mutableSetOf<Int>()
            var usedVisitCount = 0

            // Only parse up to the requested number of days
            val maxDays = minOf(daysArray.length(), days)
            for (i in 0 until maxDays) {
                val dayObj = daysArray.getJSONObject(i)
                val dayNum = dayObj.getInt("day")
                val slotsArray = dayObj.getJSONArray("slots")

                val timeSlots = mutableListOf<TimeSlot>()

                for (j in 0 until slotsArray.length()) {
                    val slotObj = slotsArray.getJSONObject(j)
                    val startTime = slotObj.getString("start_time")
                    val durationMin = slotObj.getInt("duration_min")
                    val activity = slotObj.optString("activity", "VISIT")
                    val placeId = slotObj.optInt("place_id", -1)

                    // autoAddMeals가 false일 때 MEAL 활동 건너뛰기
                    if (activity == "MEAL" && !autoAddMeals) {
                        continue
                    }

                    val place = if (activity == "VISIT" && placeId >= 0 && placeId < places.size) {
                        // ✅ VISIT인 경우 중복 방지 + 선택한 장소 수만큼만 사용
                        if (usedPlaceIds.contains(placeId)) {
                            continue
                        }
                        if (usedVisitCount >= places.size) {
                            continue
                        }
                        usedPlaceIds.add(placeId)
                        usedVisitCount++
                        places[placeId]
                    } else if (activity == "VISIT") {
                        // 비정상 id인 VISIT는 무시
                        continue
                    } else {
                        // MEAL 등 place 없는 activity
                        null
                    }

                    val endTime = calculateEndTime(startTime, durationMin)

                    timeSlots.add(
                        TimeSlot(
                            startTime = startTime,
                            endTime = endTime,
                            place = place,
                            activity = activity,
                            duration = durationMin
                        )
                    )
                }

                schedules.add(
                    DaySchedule(
                        day = dayNum,
                        timeSlots = timeSlots
                    )
                )
            }

            schedules
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse GPT response", e)
            generateFallbackItinerary(places, days, autoAddMeals).days
        }
    }

    private fun generateFallbackItinerary(
        places: List<Place>,
        days: Int,
        autoAddMeals: Boolean,
        config: ItineraryConfig = ItineraryConfig()
    ): Itinerary {
        Log.d(
            TAG,
            "Using fallback itinerary generation for $days days with ${places.size} places, autoAddMeals=$autoAddMeals, config=$config"
        )

        // 카테고리별로 분리
        val foodPlaces = places.filter { it.category == Category.FOOD }.toMutableList()
        val cafePlaces = places.filter { it.category == Category.CAFE }.toMutableList()
        val nightPlaces = places.filter { it.category == Category.NIGHT }.toMutableList()
        val stayPlaces = places.filter { it.category == Category.STAY }
        val otherPlaces = places.filter {
            it.category !in setOf(Category.FOOD, Category.CAFE, Category.NIGHT, Category.STAY)
        }.toMutableList()

        Log.d(
            TAG,
            "Category distribution: FOOD=${foodPlaces.size}, CAFE=${cafePlaces.size}, " +
                    "NIGHT=${nightPlaces.size}, STAY=${stayPlaces.size}, OTHER=${otherPlaces.size}"
        )

        val schedules = mutableListOf<DaySchedule>()
        var foodIndex = 0
        var cafeIndex = 0
        var otherIndex = 0
        var nightIndex = 0

        val morningEnd = LocalTime.of(12, 0)
        val lunchStart = LocalTime.of(12, 0)
        val lunchEnd = LocalTime.of(13, 0)
        val afternoonEnd = LocalTime.of(18, 0)
        val dinnerStart = LocalTime.of(18, 0)
        val dinnerEnd = LocalTime.of(19, 0)
        val baseNightEnd = LocalTime.parse(config.defaultEnd)
        val defaultStart = LocalTime.parse(config.defaultStart)
        val lastDayEndOverride = config.lastDayEndOverride?.let { LocalTime.parse(it) }

        for (dayIndex in 0 until days) {
            val slots = mutableListOf<TimeSlot>()
            // ✅ 현실적인 시작 시간: defaultStart (지금 기준 반영됨)
            var currentTime = defaultStart

            // 오늘 사용할 수 있는 진짜 종료 시각 (마지막 날만 override)
            val todayNightEnd = if (dayIndex == days - 1 && lastDayEndOverride != null) {
                lastDayEndOverride
            } else {
                baseNightEnd
            }

            // ===== 오전 (10:00-12:00) =====
            while (currentTime.isBefore(morningEnd) && currentTime.isBefore(todayNightEnd)) {
                val nextPlace = when {
                    otherIndex < otherPlaces.size -> otherPlaces[otherIndex++]
                    foodIndex < foodPlaces.size && currentTime.isBefore(LocalTime.of(11, 0)) -> foodPlaces[foodIndex++]
                    cafeIndex < cafePlaces.size -> cafePlaces[cafeIndex++]
                    else -> break
                }

                val duration = getDurationForCategory(nextPlace.category)
                val endTime = currentTime.plusMinutes(duration.toLong())

                val segmentEnd = if (todayNightEnd.isBefore(morningEnd)) todayNightEnd else morningEnd

                // 점심(12:00) 또는 todayNightEnd 전에 끝나야 함
                if (!endTime.isAfter(segmentEnd)) {
                    slots.add(createTimeSlot(nextPlace, currentTime, duration))
                    currentTime = endTime.plusMinutes(10) // 이동 시간 10분
                } else {
                    // 다음 장소는 점심 후로 미루기
                    when (nextPlace.category) {
                        Category.FOOD -> foodIndex--
                        Category.CAFE -> cafeIndex--
                        else -> otherIndex--
                    }
                    break
                }
            }

            // 오늘 종료 시간이 점심 이전이면 더 이상 배치 불가
            if (!todayNightEnd.isAfter(morningEnd)) {
                schedules.add(DaySchedule(day = dayIndex + 1, timeSlots = slots))
                continue
            }

            // ===== 점심 (12:00-13:00) =====
            currentTime = lunchStart
            if (todayNightEnd.isAfter(lunchStart)) {
                if (foodIndex < foodPlaces.size) {
                    val place = foodPlaces[foodIndex++]
                    val duration = 60
                    val endTime = currentTime.plusMinutes(duration.toLong())
                    if (!endTime.isAfter(minOf(todayNightEnd, lunchEnd))) {
                        slots.add(createTimeSlot(place, currentTime, duration))
                    }
                } else if (autoAddMeals) {
                    val duration = 60
                    val endTime = currentTime.plusMinutes(duration.toLong())
                    if (!endTime.isAfter(minOf(todayNightEnd, lunchEnd))) {
                        slots.add(createMealSlot(currentTime, duration))
                    }
                }
            }
            currentTime = lunchEnd
            if (!todayNightEnd.isAfter(currentTime)) {
                schedules.add(DaySchedule(day = dayIndex + 1, timeSlots = slots))
                continue
            }

            // ===== 오후 (13:00-18:00) =====
            while (currentTime.isBefore(afternoonEnd) && currentTime.isBefore(todayNightEnd)) {
                val nextPlace = when {
                    otherIndex < otherPlaces.size -> otherPlaces[otherIndex++]
                    cafeIndex < cafePlaces.size -> cafePlaces[cafeIndex++]
                    foodIndex < foodPlaces.size -> foodPlaces[foodIndex++]
                    else -> break
                }

                val duration = getDurationForCategory(nextPlace.category)
                val endTime = currentTime.plusMinutes(duration.toLong())

                val segmentEnd = if (todayNightEnd.isBefore(afternoonEnd)) todayNightEnd else afternoonEnd

                // 저녁(18:00) 또는 todayNightEnd 전에 끝나야 함
                if (!endTime.isAfter(segmentEnd)) {
                    slots.add(createTimeSlot(nextPlace, currentTime, duration))
                    currentTime = endTime.plusMinutes(10)
                } else {
                    when (nextPlace.category) {
                        Category.FOOD -> foodIndex--
                        Category.CAFE -> cafeIndex--
                        else -> otherIndex--
                    }
                    break
                }
            }

            if (!todayNightEnd.isAfter(afternoonEnd)) {
                schedules.add(DaySchedule(day = dayIndex + 1, timeSlots = slots))
                continue
            }

            // ===== 저녁 (18:00-19:00) =====
            currentTime = dinnerStart
            if (todayNightEnd.isAfter(currentTime)) {
                val segmentEnd = if (todayNightEnd.isBefore(dinnerEnd)) todayNightEnd else dinnerEnd
                if (foodIndex < foodPlaces.size) {
                    val place = foodPlaces[foodIndex++]
                    val duration = 60
                    val endTime = currentTime.plusMinutes(duration.toLong())
                    if (!endTime.isAfter(segmentEnd)) {
                        slots.add(createTimeSlot(place, currentTime, duration))
                    } else {
                        // 못 넣으면 되돌리기
                        foodIndex--
                    }
                } else if (autoAddMeals) {
                    val duration = 60
                    val endTime = currentTime.plusMinutes(duration.toLong())
                    if (!endTime.isAfter(segmentEnd)) {
                        slots.add(createMealSlot(currentTime, duration))
                    }
                }
            }
            currentTime = dinnerEnd
            if (!todayNightEnd.isAfter(currentTime)) {
                schedules.add(DaySchedule(day = dayIndex + 1, timeSlots = slots))
                continue
            }

            // ===== 야간 (19:00-todayNightEnd) =====
            while (currentTime.isBefore(todayNightEnd)) {
                val nextPlace = when {
                    nightIndex < nightPlaces.size -> nightPlaces[nightIndex++]
                    cafeIndex < cafePlaces.size -> cafePlaces[cafeIndex++]
                    foodIndex < foodPlaces.size -> foodPlaces[foodIndex++]
                    else -> break
                }

                val duration = getDurationForCategory(nextPlace.category)
                val endTime = currentTime.plusMinutes(duration.toLong())

                if (!endTime.isAfter(todayNightEnd)) {
                    slots.add(createTimeSlot(nextPlace, currentTime, duration))
                    currentTime = endTime.plusMinutes(10)
                } else {
                    when (nextPlace.category) {
                        Category.NIGHT -> nightIndex--
                        Category.CAFE -> cafeIndex--
                        Category.FOOD -> foodIndex--
                        else -> {}
                    }
                    break
                }
            }

            schedules.add(DaySchedule(day = dayIndex + 1, timeSlots = slots))
        }

        return Itinerary(days = schedules)
    }

    private fun getDurationForCategory(category: Category): Int = when (category) {
        Category.FOOD -> 60
        Category.CAFE -> 45
        Category.CULTURE, Category.EXPERIENCE -> 75
        Category.PHOTO -> 45
        Category.HEALING, Category.SHOPPING -> 60
        Category.NIGHT -> 60
        Category.STAY -> 0
    }

    private fun createTimeSlot(place: Place, startTime: LocalTime, durationMin: Int): TimeSlot {
        return TimeSlot(
            startTime = startTime.toString(),
            endTime = startTime.plusMinutes(durationMin.toLong()).toString(),
            place = place,
            activity = "VISIT",
            duration = durationMin
        )
    }

    private fun createMealSlot(startTime: LocalTime, durationMin: Int): TimeSlot {
        return TimeSlot(
            startTime = startTime.toString(),
            endTime = startTime.plusMinutes(durationMin.toLong()).toString(),
            place = null,
            activity = "MEAL",
            duration = durationMin
        )
    }

    private fun sanitizeJson(raw: String): String {
        val cleaned = raw.replace("```json", "").replace("```", "").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        return if (start >= 0 && end > start) {
            cleaned.substring(start, end + 1)
        } else {
            "{}"
        }
    }

    private fun calculateEndTime(startTime: String, durationMin: Int): String {
        return try {
            val start = LocalTime.parse(startTime)
            start.plusMinutes(durationMin.toLong()).toString()
        } catch (e: Exception) {
            startTime
        }
    }

    // ==========================
    // ✅ Day1 시작 시간 보정
    // ==========================
    private fun shiftDay1ToConfigStart(
        schedules: List<DaySchedule>,
        config: ItineraryConfig,
        gapMinutes: Int = 10
    ): List<DaySchedule> {
        val idx = schedules.indexOfFirst { it.day == 1 }
        if (idx == -1) return schedules
        val day1 = schedules[idx]
        if (day1.timeSlots.isEmpty()) return schedules

        val adjustedDay1 = recalcDaySequential(
            daySchedule = day1,
            startFrom = config.defaultStart,
            gapMinutes = gapMinutes
        )

        val result = schedules.toMutableList()
        result[idx] = adjustedDay1
        return result
    }

    /**
     * ✅ 하루짜리 일정의 시간대를 위에서 아래로 다시 계산하는 함수
     * - 편집 화면에서 duration 수정 후 호출하면, 그 Day 전체 시간이 자동으로 밀림
     */
    fun recalcDaySequential(
        daySchedule: DaySchedule,
        startFrom: String? = null,
        gapMinutes: Int = 10
    ): DaySchedule {
        val slots = daySchedule.timeSlots
        if (slots.isEmpty()) return daySchedule

        val baseStart = startFrom ?: slots.first().startTime
        var current = LocalTime.parse(baseStart)

        val newSlots: MutableList<TimeSlot> = slots.map { slot ->
            val duration = slot.duration
            val start = current
            val end = start.plusMinutes(duration.toLong())

            val newSlot = slot.copy(
                startTime = start.toString(),
                endTime = end.toString()
            )
            current = end.plusMinutes(gapMinutes.toLong())
            newSlot
        }.toMutableList()

        return daySchedule.copy(timeSlots = newSlots)
    }
}
