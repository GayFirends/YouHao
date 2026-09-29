package com.youhao.fueltrack.ui.records

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.youhao.fueltrack.domain.calc.FuelCalculationTarget
import com.youhao.fueltrack.domain.calc.FuelDiscountType
import com.youhao.fueltrack.domain.calc.FuelPaymentCalculation
import com.youhao.fueltrack.ui.components.youHaoFieldColors
import com.youhao.fueltrack.ui.formatMoney
import com.youhao.fueltrack.ui.formatNumber
import com.youhao.fueltrack.ui.theme.numeric

/** One set of fields shared by input, calculation, and saving the record. */
@Composable
fun FuelAmountFields(form: FuelAmountForm, onChange: (FuelAmountForm) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val finishInput = { focus.clearFocus(); keyboard?.hide(); Unit }
    val autoAmount = form.target == FuelCalculationTarget.AMOUNT
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
            Text("自动算", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FuelCalculationTarget.entries.forEach { target ->
                FilterChip(selected = form.target == target,
                    onClick = { finishInput(); onChange(form.switchTarget(target)) },
                    label = { Text(when (target) {
                        FuelCalculationTarget.AMOUNT -> "实付"
                        FuelCalculationTarget.UNIT_PRICE -> "单价"
                        FuelCalculationTarget.LITERS -> "油量"
                    }) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("实付金额", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (autoAmount) Text("自动", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("¥", style = MaterialTheme.typography.headlineSmall)
            BasicTextField(
                value = form.amountText,
                onValueChange = { onChange(form.edit(FuelAmountInput.AMOUNT, it)) },
                readOnly = autoAmount,
                textStyle = MaterialTheme.typography.displaySmall.copy(fontSize = 48.sp, color = MaterialTheme.colorScheme.onSurface).numeric(),
                singleLine = true,
                modifier = Modifier.weight(1f).padding(vertical = 6.dp).semantics {
                    contentDescription = if (autoAmount) "实付金额，元，自动计算" else "实付金额，元"
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                decorationBox = { inner ->
                    Box {
                        if (form.amountText.isEmpty()) Text(if (autoAmount) "—" else "0.00",
                            style = MaterialTheme.typography.displaySmall.copy(fontSize = 48.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        inner()
                    }
                },
            )
        }
        Text(when (form.target) {
            FuelCalculationTarget.AMOUNT -> "填写油量和单价，实付自动更新"
            FuelCalculationTarget.UNIT_PRICE -> "填写油量和实付，反算优惠前单价"
            FuelCalculationTarget.LITERS -> "填写单价和实付，油量自动更新"
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        val litersField: @Composable (Modifier) -> Unit = { fieldModifier ->
            AmountField("加油量", "L", form.litersText, form.target == FuelCalculationTarget.LITERS,
                { onChange(form.edit(FuelAmountInput.LITERS, it)) }, fieldModifier)
        }
        val priceField: @Composable (Modifier) -> Unit = { fieldModifier ->
            AmountField("表显单价", "元/L", form.priceText, form.target == FuelCalculationTarget.UNIT_PRICE,
                { onChange(form.edit(FuelAmountInput.UNIT_PRICE, it)) }, fieldModifier)
        }
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 320.dp && LocalDensity.current.fontScale <= 1.15f) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    litersField(Modifier.weight(1f))
                    priceField(Modifier.weight(1f))
                }
            } else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                litersField(Modifier.fillMaxWidth())
                priceField(Modifier.fillMaxWidth())
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FuelDiscountType.entries.forEach { type ->
                FilterChip(selected = form.discountType == type,
                    onClick = { finishInput(); onChange(form.switchDiscount(type)) },
                    label = { Text(when (type) {
                        FuelDiscountType.NONE -> "无优惠"
                        FuelDiscountType.FIXED -> "立减"
                        FuelDiscountType.PER_LITER -> "每升减"
                        FuelDiscountType.RATE -> "打折"
                    }) })
            }
        }
        if (form.discountType != FuelDiscountType.NONE) {
            OutlinedTextField(form.discount, { onChange(form.edit(FuelAmountInput.DISCOUNT, it)) },
                Modifier.fillMaxWidth(), label = { Text(when (form.discountType) {
                    FuelDiscountType.FIXED -> "共优惠 · 元"
                    FuelDiscountType.PER_LITER -> "每升优惠 · 元/L"
                    FuelDiscountType.RATE -> "折扣 · 折（如 9.5）"
                    FuelDiscountType.NONE -> "优惠"
                }) }, singleLine = true, colors = youHaoFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }
        if (form.discountType == FuelDiscountType.PER_LITER || form.discountType == FuelDiscountType.RATE) {
            OutlinedTextField(form.coupon, { onChange(form.edit(FuelAmountInput.COUPON, it)) },
                Modifier.fillMaxWidth(), label = { Text("再减优惠券 · 元（选填）") }, singleLine = true,
                colors = youHaoFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            Text("先按${if (form.discountType == FuelDiscountType.RATE) "折扣" else "每升优惠"}计算，再减优惠券。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Column(Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when (val calculation = form.calculation) {
                is FuelPaymentCalculation.Success -> {
                    Text("表显 ${formatMoney(calculation.result.pumpAmount)} · 优惠 ${formatMoney(calculation.result.discountAmount)}",
                        style = MaterialTheme.typography.bodySmall.numeric(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("实付单价 ${formatNumber(calculation.result.paidPrice, 4)} 元/L",
                        style = MaterialTheme.typography.bodySmall.numeric(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is FuelPaymentCalculation.Invalid -> Text(calculation.message,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                FuelPaymentCalculation.Incomplete -> Text("填好已知两项及优惠后即可保存",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        HorizontalDivider(Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun AmountField(
    label: String,
    unit: String,
    value: String,
    automatic: Boolean,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(value, onChange, modifier.semantics {
        contentDescription = "$label，$unit${if (automatic) "，自动计算" else ""}"
    }, label = { Text(if (automatic) "$label · 自动" else label) },
        suffix = { Text(unit, style = MaterialTheme.typography.bodySmall) },
        placeholder = { if (automatic) Text("—") }, readOnly = automatic, singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.numeric(), colors = youHaoFieldColors(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
}
