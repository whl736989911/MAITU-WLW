package com.fireplatform.simexpiry;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class SimExpiryCalendarTest {
    @Test void windowUsesCalendarDayDifferenceWithoutOverflowOrFutureFalsePositives(){LocalDate today=LocalDate.of(2026,10,29);assertThat(SimExpiryCommands.window(today.plusDays(30),today,30)).isTrue();assertThat(SimExpiryCommands.window(today.plusDays(31),today,30)).isFalse();assertThat(SimExpiryCommands.window(today.minusDays(1),today,0)).isTrue();assertThat(SimExpiryCommands.window(LocalDate.of(9999,12,31),LocalDate.of(1,1,1),0)).isFalse();assertThat(SimExpiryCommands.window(LocalDate.of(1,1,1),LocalDate.of(9999,12,31),Integer.MAX_VALUE)).isTrue();}
}
