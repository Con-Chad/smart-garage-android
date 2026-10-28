package io.github.conchad.smartgarage.feature.settings.obd

import com.google.common.truth.Truth.assertThat
import io.github.conchad.smartgarage.core.format.Formatter
import io.github.conchad.smartgarage.core.obd.DistanceUnit
import io.github.conchad.smartgarage.core.obd.OdometerKind
import io.github.conchad.smartgarage.core.obd.OdometerProfiles
import io.github.conchad.smartgarage.core.obd.OdometerUnits
import org.junit.Test

/** D-A74: the dash check behind "What does your dash say?" and the screen's number parsing. */
class ObdCalibrationTest {

    private val honda = OdometerProfiles.HONDA_CLUSTER

    @Test
    fun `same odometer within a few km or a tenth of a percent matches`() {
        assertThat(ObdCalibration.check(84_210, 84_212.0, honda, "x")).isEqualTo(Calibration.Matches)
        assertThat(ObdCalibration.check(84_210, 84_280.0, honda, "x")).isEqualTo(Calibration.Matches)
        assertThat(ObdCalibration.check(120, 122.0, honda, "x")).isEqualTo(Calibration.Matches)
    }

    @Test
    fun `a car counting miles read as km switches the unit to miles`() {
        // Cluster says 52,325 mi; the profile thought the 52,325 it read was km.
        val dashKm = OdometerUnits.displayToKm(52_325.0, miles = true)
        val result = ObdCalibration.check(52_325, dashKm, honda, "Honda (calibrated)")
        assertThat(result).isInstanceOf(Calibration.Fix::class.java)
        result as Calibration.Fix
        assertThat(result.factor).isEqualTo(CleanFactor.MILES_AS_KM)
        assertThat(result.profile.unit).isEqualTo(DistanceUnit.MI)
        assertThat(result.profile.multiplier).isEqualTo(1.0)
        // A built-in becomes a named custom copy, so resolved() no longer turns it back.
        assertThat(result.profile.kind).isEqualTo(OdometerKind.CUSTOM)
        assertThat(result.profile.name).isEqualTo("Honda (calibrated)")
        assertThat(result.profile.resolved()).isEqualTo(result.profile)
        assertThat(result.profile.request).isEqualTo(honda.request)
        assertThat(result.profile.expression).isEqualTo(honda.expression)
    }

    @Test
    fun `a km car under a miles profile switches back to km`() {
        val profile = OdometerProfiles.custom(honda).copy(unit = DistanceUnit.MI, name = "Pasted")
        // Read 84,210 "mi" = 135,522 km; the dash shows 84,210 km.
        val readKm = OdometerUnits.toKm(84_210.0, DistanceUnit.MI)!!
        val result = ObdCalibration.check(readKm, 84_210.0, profile, "unused") as Calibration.Fix
        assertThat(result.factor).isEqualTo(CleanFactor.KM_AS_MILES)
        assertThat(result.profile.unit).isEqualTo(DistanceUnit.KM)
        assertThat(result.profile.name).isEqualTo("Pasted")
        assertThat(OdometerUnits.toKm(84_210.0, result.profile.unit, result.profile.multiplier)).isEqualTo(84_210)
    }

    @Test
    fun `km reported as miles on a km profile scales the multiplier instead`() {
        // Already km and the dash is smaller by 1.609: the unit can't switch the other way, so the multiplier does.
        val result = ObdCalibration.check(135_522, 84_210.0, honda, "c") as Calibration.Fix
        assertThat(result.factor).isEqualTo(CleanFactor.KM_AS_MILES)
        assertThat(result.profile.unit).isEqualTo(DistanceUnit.KM)
        assertThat(result.profile.multiplier).isWithin(1e-9).of(1 / Formatter.KM_PER_MILE)
    }

    @Test
    fun `powers of ten scale the multiplier`() {
        val standardMissingTenth = OdometerProfiles.STANDARD.copy(kind = OdometerKind.CUSTOM, expression = "[B3:B6]", name = "WiCAN generic")
        val tenth = ObdCalibration.check(842_100, 84_210.0, standardMissingTenth, "c") as Calibration.Fix
        assertThat(tenth.factor).isEqualTo(CleanFactor.TENTH)
        assertThat(tenth.profile.multiplier).isWithin(1e-9).of(0.1)

        val ten = ObdCalibration.check(8_421, 84_215.0, honda, "c") as Calibration.Fix
        assertThat(ten.factor).isEqualTo(CleanFactor.TIMES_TEN)
        assertThat(ten.profile.multiplier).isWithin(1e-9).of(10.0)

        val hundredth = ObdCalibration.check(8_421_000, 84_210.0, honda, "c") as Calibration.Fix
        assertThat(hundredth.factor).isEqualTo(CleanFactor.HUNDREDTH)
    }

    @Test
    fun `an existing multiplier is multiplied, not replaced`() {
        val profile = OdometerProfiles.custom(honda).copy(multiplier = 0.5, name = "half")
        val fix = ObdCalibration.check(4_210, 42_100.0, profile, "c") as Calibration.Fix
        assertThat(fix.profile.multiplier).isWithin(1e-9).of(5.0)
    }

    @Test
    fun `anything else is no clean factor and says by how much`() {
        val result = ObdCalibration.check(84_210, 90_000.0, honda, "c")
        assertThat(result).isInstanceOf(Calibration.NoCleanFactor::class.java)
        assertThat((result as Calibration.NoCleanFactor).differenceKm).isWithin(1e-9).of(5_790.0)
        // ×1.5 is close to neither 1.609 nor 1.
        assertThat(ObdCalibration.check(10_000, 15_000.0, honda, "c")).isInstanceOf(Calibration.NoCleanFactor::class.java)
    }

    @Test
    fun `the tolerance allows dash rounding but not a different factor`() {
        // 1.4% off ×10 still counts as ×10 (driven since the read, dash rounding).
        assertThat((ObdCalibration.check(8_421, 85_390.0, honda, "c") as Calibration.Fix).factor).isEqualTo(CleanFactor.TIMES_TEN)
        // 3% off doesn't.
        assertThat(ObdCalibration.check(8_421, 86_740.0, honda, "c")).isInstanceOf(Calibration.NoCleanFactor::class.java)
    }

    @Test
    fun `codes cleared, zero and nonsense readings are not checked`() {
        assertThat(ObdCalibration.check(84_210, 52_000.0, OdometerProfiles.CODES_CLEARED, "c")).isEqualTo(Calibration.NotApplicable)
        assertThat(ObdCalibration.check(0, 52_000.0, honda, "c")).isEqualTo(Calibration.NotApplicable)
        assertThat(ObdCalibration.check(84_210, 0.0, honda, "c")).isEqualTo(Calibration.NotApplicable)
        assertThat(ObdCalibration.check(84_210, Double.NaN, honda, "c")).isEqualTo(Calibration.NotApplicable)
    }

    @Test
    fun `odometer text in either locale`() {
        assertThat(ObdInputs.odometer("84512")).isEqualTo(84_512.0)
        assertThat(ObdInputs.odometer("84,512")).isEqualTo(84_512.0)
        assertThat(ObdInputs.odometer("84.512")).isEqualTo(84_512.0)
        assertThat(ObdInputs.odometer("84 512")).isEqualTo(84_512.0)
        assertThat(ObdInputs.odometer("1.084.512")).isEqualTo(1_084_512.0)
        assertThat(ObdInputs.odometer("84512.4")).isEqualTo(84_512.4)
        assertThat(ObdInputs.odometer("84512,4")).isEqualTo(84_512.4)
        assertThat(ObdInputs.odometer("84,512.4")).isEqualTo(84_512.4)
        assertThat(ObdInputs.odometer("84.512,4")).isEqualTo(84_512.4)
        assertThat(ObdInputs.odometer(" ")).isNull()
        assertThat(ObdInputs.odometer("abc")).isNull()
    }

    @Test
    fun `upload fields`() {
        assertThat(ObdInputs.minutes("5")).isEqualTo(5)
        assertThat(ObdInputs.minutes("")).isNull()
        assertThat(ObdInputs.distance("12,5")).isEqualTo(12.5)
        assertThat(ObdInputs.distance("-3")).isNull()
        assertThat(ObdInputs.distanceText(25.0)).isEqualTo("25")
        assertThat(ObdInputs.distanceText(12.5)).isEqualTo("12.5")
    }

    @Test
    fun `profile picker lists the built-ins and the stored custom one`() {
        assertThat(ObdAdapterViewModel.profileChoices(honda)).containsExactlyElementsIn(OdometerProfiles.builtIns).inOrder()
        val custom = OdometerProfiles.custom(honda).copy(name = "Mine")
        assertThat(ObdAdapterViewModel.profileChoices(custom)).isEqualTo(OdometerProfiles.builtIns + custom)
    }
}
