package com.example.project_2.ui.itinerary

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.project_2.domain.ItineraryUseCase
import com.example.project_2.domain.model.*
import kotlinx.coroutines.launch
import org.burnoutcrew.reorderable.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItineraryScreen(
    selectedPlaces: List<Place>,
    filter: FilterState,
    autoAddMeals: Boolean = false,
    onBack: () -> Unit,
    onNavigateToMap: (Itinerary) -> Unit = {},
    onSaveItinerary: (Itinerary) -> Unit = {}
) {
    var itinerary by remember { mutableStateOf<Itinerary?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var selectedDayTab by remember { mutableStateOf(0) }
    var isEditMode by remember { mutableStateOf(false) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var itineraryName by remember { mutableStateOf("") }
    var showMoveDayDialog by remember { mutableStateOf(false) }
    var slotToMove by remember { mutableStateOf<Pair<Int, TimeSlot>?>(null) }
    var showAddPlaceDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // 🔧 UseCase를 remember로 유지 (recalc에도 재사용)
    val useCase = remember { ItineraryUseCase() }

    // 일정 생성
    LaunchedEffect(selectedPlaces, autoAddMeals) {
        isLoading = true
        itinerary = useCase.generateItinerary(selectedPlaces, filter, autoAddMeals)
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${filter.duration.toDays()}일 여행 일정") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "뒤로")
                    }
                },
                actions = {
                    if (isEditMode) {
                        IconButton(onClick = { isEditMode = false }) {
                            Icon(Icons.Default.Save, "완료")
                        }
                    } else {
                        IconButton(onClick = { isEditMode = true }) {
                            Icon(Icons.Default.Edit, "편집")
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (itinerary != null) {
                BottomAppBar {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { itinerary?.let { onNavigateToMap(it) } },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Map, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("지도 보기")
                        }

                        Button(
                            onClick = {
                                itinerary?.let {
                                    // 기본 이름 생성: "N일 여행 일정"
                                    itineraryName = "${filter.duration.toDays()}일 여행 일정"
                                    showSaveDialog = true
                                }
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Save, null, Modifier.size(20.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("일정 저장")
                        }
                    }
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                isLoading -> {
                    LoadingView()
                }
                itinerary != null -> {
                    Column(Modifier.fillMaxSize()) {
                        // Day 탭
                        TabRow(selectedTabIndex = selectedDayTab) {
                            itinerary!!.days.forEachIndexed { index, day ->
                                Tab(
                                    selected = selectedDayTab == index,
                                    onClick = { selectedDayTab = index },
                                    text = { Text("Day ${day.day}") }
                                )
                            }
                        }

                        // 선택된 Day의 일정
                        if (selectedDayTab < itinerary!!.days.size) {
                            DayScheduleView(
                                day = itinerary!!.days[selectedDayTab],
                                dayIndex = selectedDayTab,
                                isEditMode = isEditMode,
                                totalDays = itinerary!!.days.size,
                                onDeleteSlot = { slot ->
                                    // TimeSlot 삭제
                                    itinerary = itinerary?.copy(
                                        days = itinerary!!.days.mapIndexed { index, day ->
                                            if (index == selectedDayTab) {
                                                day.copy(
                                                    timeSlots = day.timeSlots.toMutableList().apply {
                                                        remove(slot)
                                                    }
                                                )
                                            } else {
                                                day
                                            }
                                        }
                                    )
                                },
                                onReorder = { from, to ->
                                    // TimeSlot 순서 변경
                                    itinerary = itinerary?.copy(
                                        days = itinerary!!.days.mapIndexed { index, day ->
                                            if (index == selectedDayTab) {
                                                day.copy(
                                                    timeSlots = day.timeSlots.toMutableList().apply {
                                                        add(to, removeAt(from))
                                                    }
                                                )
                                            } else {
                                                day
                                            }
                                        }
                                    )
                                },
                                onMoveToDay = { slot ->
                                    slotToMove = selectedDayTab to slot
                                    showMoveDayDialog = true
                                },
                                onAddPlace = {
                                    showAddPlaceDialog = true
                                },
                                // 🔥 체류 시간 변경 → 아래 일정 자동 재계산
                                onChangeDuration = { slotIndex, newDuration ->
                                    val current = itinerary ?: return@DayScheduleView
                                    val days = current.days.toMutableList()
                                    val targetDay = days[selectedDayTab]

                                    if (slotIndex !in targetDay.timeSlots.indices) return@DayScheduleView

                                    // duration 수정
                                    targetDay.timeSlots[slotIndex].duration = newDuration

                                    // 하루 전체 시간 다시 계산
                                    val recalcedDay = useCase.recalcDaySequential(
                                        daySchedule = targetDay,
                                        startFrom = targetDay.timeSlots.firstOrNull()?.startTime,
                                        gapMinutes = 10
                                    )

                                    days[selectedDayTab] = recalcedDay
                                    itinerary = current.copy(days = days)
                                }
                            )
                        }
                    }
                }
                else -> {
                    ErrorView()
                }
            }
        }
    }

    // Day 간 이동 다이얼로그
    if (showMoveDayDialog && slotToMove != null && itinerary != null) {
        val (fromDay, slot) = slotToMove!!
        AlertDialog(
            onDismissRequest = { showMoveDayDialog = false },
            title = { Text("다른 날로 이동") },
            text = {
                Column {
                    Text("이동할 날짜를 선택하세요")
                    Spacer(Modifier.height(16.dp))
                    itinerary!!.days.forEachIndexed { index, day ->
                        if (index != fromDay) {
                            OutlinedButton(
                                onClick = {
                                    itinerary = itinerary?.copy(
                                        days = itinerary!!.days.mapIndexed { dayIndex, d ->
                                            when (dayIndex) {
                                                fromDay -> d.copy(
                                                    timeSlots = d.timeSlots.toMutableList().apply {
                                                        remove(slot)
                                                    }
                                                )
                                                index -> d.copy(
                                                    timeSlots = d.timeSlots.toMutableList().apply {
                                                        add(slot)
                                                    }
                                                )
                                                else -> d
                                            }
                                        }
                                    )
                                    showMoveDayDialog = false
                                    slotToMove = null
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Text("Day ${day.day} (${day.timeSlots.size}개 일정)")
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    showMoveDayDialog = false
                    slotToMove = null
                }) {
                    Text("취소")
                }
            }
        )
    }

    // 장소 추가 안내 다이얼로그
    if (showAddPlaceDialog) {
        AlertDialog(
            onDismissRequest = { showAddPlaceDialog = false },
            title = { Text("장소 추가") },
            text = {
                Column {
                    Text("일정 생성 중에는 장소를 추가할 수 없습니다.")
                    Spacer(Modifier.height(8.dp))
                    Text("장소를 추가하려면:", style = MaterialTheme.typography.bodyMedium)
                    Text("1. 일정을 먼저 저장하세요", style = MaterialTheme.typography.bodyMedium)
                    Text("2. 저장된 일정에서 편집 모드로 변경", style = MaterialTheme.typography.bodyMedium)
                    Text("3. 필요한 장소를 삭제하거나 순서 변경", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "또는 뒤로 가기하여 장소를 다시 선택하고 일정을 재생성하세요.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAddPlaceDialog = false }) {
                    Text("확인")
                }
            }
        )
    }

    // 저장 다이얼로그
    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("일정 저장") },
            text = {
                Column {
                    Text("일정 이름을 입력하세요")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = itineraryName,
                        onValueChange = { itineraryName = it },
                        label = { Text("일정 이름") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (itineraryName.isNotBlank()) {
                            itinerary?.let {
                                it.name = itineraryName
                                onSaveItinerary(it)
                            }
                            showSaveDialog = false
                        }
                    },
                    enabled = itineraryName.isNotBlank()
                ) {
                    Text("저장")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}

@Composable
private fun LoadingView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text("AI가 최적의 일정을 생성하는 중...")
        }
    }
}

@Composable
private fun ErrorView() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text("일정을 생성할 수 없습니다", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun DayScheduleView(
    day: DaySchedule,
    dayIndex: Int,
    isEditMode: Boolean,
    totalDays: Int,
    onDeleteSlot: (TimeSlot) -> Unit,
    onReorder: (Int, Int) -> Unit,
    onMoveToDay: (TimeSlot) -> Unit,
    onAddPlace: () -> Unit,
    onChangeDuration: (slotIndex: Int, newDuration: Int) -> Unit
) {
    val reorderableState = rememberReorderableLazyListState(
        onMove = { from, to ->
            // Subtract 1 because first item is header
            if (from.index > 0 && to.index > 0) {
                onReorder(from.index - 1, to.index - 1)
            }
        }
    )

    LazyColumn(
        state = reorderableState.listState,
        modifier = Modifier
            .fillMaxSize()
            .reorderable(reorderableState),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 헤더
        item(key = "header") {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                "Day ${day.day}",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${day.timeSlots.size}개 일정",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        Column(
                            horizontalAlignment = Alignment.End
                        ) {
                            if (isEditMode) {
                                Text(
                                    "드래그하여 순서 변경",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                                Spacer(Modifier.height(4.dp))
                                TextButton(onClick = onAddPlace) {
                                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("장소 추가 안내")
                                }
                            }
                        }
                    }
                }
            }
        }

        // 시간대별 일정
        itemsIndexed(day.timeSlots, key = { _, slot -> slot.id }) { index, slot ->
            ReorderableItem(reorderableState, key = slot.id) { isDragging ->
                TimeSlotCard(
                    slot = slot,
                    isEditMode = isEditMode,
                    isDragging = isDragging,
                    reorderableState = reorderableState,
                    onDelete = if (isEditMode) { { onDeleteSlot(slot) } } else null,
                    onChangeDuration = if (isEditMode) {
                        { newDur -> onChangeDuration(index, newDur) }
                    } else null,
                    onMoveToDay = if (isEditMode && totalDays > 1) {
                        { onMoveToDay(slot) }
                    } else null
                )
            }
        }
    }
}

@Composable
private fun TimeSlotCard(
    slot: TimeSlot,
    isEditMode: Boolean = false,
    isDragging: Boolean = false,
    reorderableState: ReorderableLazyListState? = null,
    onDelete: (() -> Unit)?,
    onChangeDuration: ((Int) -> Unit)? = null,
    onMoveToDay: (() -> Unit)? = null
) {
    var showDurationDialog by remember { mutableStateOf(false) }
    var tempDuration by remember(slot.duration) { mutableStateOf(slot.duration) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isDragging) 8.dp else 2.dp
        ),
        colors = CardDefaults.cardColors(
            containerColor = if (isDragging) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 드래그 핸들 (편집 모드일 때만)
            if (isEditMode && reorderableState != null) {
                Icon(
                    imageVector = Icons.Default.DragHandle,
                    contentDescription = "드래그",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(24.dp)
                        .detectReorder(reorderableState)
                )
            }

            // 시간 + 체류 시간
            Column(
                modifier = Modifier.width(80.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    slot.startTime,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                if (isEditMode && onChangeDuration != null) {
                    TextButton(onClick = { showDurationDialog = true }) {
                        Text(
                            "${slot.duration}분",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Text(
                        "${slot.duration}분",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Divider(
                modifier = Modifier
                    .width(2.dp)
                    .height(50.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            // 장소 정보
            Column(Modifier.weight(1f)) {
                if (slot.place != null) {
                    Text(
                        slot.place.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        getCategoryEmoji(slot.place.category) + " ${slot.place.category}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (slot.place.address != null) {
                        Text(
                            slot.place.address,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    // 식사/이동 등
                    Text(
                        getActivityName(slot.activity),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // 이동 정보
                slot.travelInfo?.let { travel ->
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "🚶 다음까지: ${travel.distance}km, ${travel.duration}분",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 다른 날로 이동 버튼 (편집 모드일 때만)
                if (isEditMode && onMoveToDay != null) {
                    IconButton(onClick = onMoveToDay) {
                        Icon(
                            Icons.Default.CalendarToday,
                            contentDescription = "다른 날로 이동",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 삭제 버튼 (편집 모드일 때만)
                if (isEditMode && onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "삭제",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    }

    // 🔥 체류 시간 수정 다이얼로그
    if (showDurationDialog && onChangeDuration != null) {
        // 텍스트 입력용 상태 (중간 상태를 그대로 보여주기 위함)
        var durationText by remember { mutableStateOf(slot.duration.toString()) }
        var errorText by remember { mutableStateOf<String?>(null) }

        AlertDialog(
            onDismissRequest = { showDurationDialog = false },
            title = { Text("체류 시간 수정") },
            text = {
                Column {
                    Text("이 장소에서 얼마나 있을까요?")
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = durationText,
                        onValueChange = { newValue ->
                            // 숫자만 허용
                            val filtered = newValue.filter { it.isDigit() }.take(3)
                            durationText = filtered
                            errorText = null
                        },
                        label = { Text("분 단위 (10~300)") },
                        singleLine = true,
                        isError = errorText != null
                    )
                    if (errorText != null) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = errorText!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = durationText.toIntOrNull()
                    if (v == null || v !in 10..300) {
                        errorText = "10~300 사이의 숫자를 입력하세요."
                    } else {
                        onChangeDuration(v)
                        showDurationDialog = false
                    }
                }) {
                    Text("적용")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDurationDialog = false }) {
                    Text("취소")
                }
            }
        )
    }
}

private fun getCategoryEmoji(category: Category): String = when (category) {
    Category.FOOD -> "🍜"
    Category.CAFE -> "☕"
    Category.PHOTO -> "📸"
    Category.CULTURE -> "🏛"
    Category.SHOPPING -> "🛍"
    Category.HEALING -> "🌳"
    Category.EXPERIENCE -> "🧪"
    Category.NIGHT -> "🌃"
    Category.STAY -> "🏨"
}

private fun getActivityName(activity: String): String = when (activity) {
    "MEAL" -> "🍽️ 식사 시간"
    "TRANSPORT" -> "🚶 이동"
    "REST" -> "☕ 휴식"
    else -> activity
}
