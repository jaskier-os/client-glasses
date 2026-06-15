//==============================================================================
//
// Copyright (c) Qualcomm Technologies, Inc.
// All Rights Reserved.
// Confidential and Proprietary - Qualcomm Technologies, Inc.
//
//==============================================================================

#ifndef BFLOAT16_H
#define BFLOAT16_H

#include <cassert>
#include <cstdint>
#include <cmath>
#include <limits>
#include <type_traits>

#include "builtin_intrinsics.h"

#include "weak_linkage.h"
#include "macros_attribute.h"

PUSH_VISIBILITY(default)

struct API_EXPORT BFloat16 {
  public:
    constexpr BFloat16() noexcept : d(0) {}
    constexpr BFloat16(float f) noexcept;
    // 'noexcept' on defaulted operations is enforced with static_assert af6er class. Do not add here.
    constexpr BFloat16(const BFloat16 &f) = default;
    constexpr BFloat16 &operator=(const BFloat16 &f) = default;
    constexpr BFloat16(BFloat16 &&f) = default;
    constexpr BFloat16 &operator=(BFloat16 &&f) = default;
    ~BFloat16() = default;

    constexpr bool is_zero() const noexcept;
    constexpr bool is_neg() const noexcept;
    constexpr bool is_inf() const noexcept;
    constexpr bool is_nan() const noexcept;
    constexpr bool is_subnorm() const noexcept;
    constexpr bool is_norm() const noexcept;
    constexpr bool is_finite() const noexcept;

    constexpr int16_t exp() const noexcept;
    constexpr uint16_t frac() const noexcept;
    constexpr uint16_t raw() const noexcept { return d; }

    static constexpr int exp_max() noexcept { return 127; }
    static constexpr int exp_min() noexcept { return -126; }
    static constexpr int16_t bias() noexcept { return 127; }

    static constexpr BFloat16 zero(bool neg = false) noexcept;
    static constexpr BFloat16 qnan() noexcept;
    static constexpr BFloat16 snan() noexcept;
    static constexpr BFloat16 inf(bool neg = false) noexcept;

    static constexpr BFloat16 from_raw(uint16_t v) noexcept;

    constexpr operator float() const noexcept;

  private:
    union {
        uint16_t d;
        struct {
            uint16_t mantissa : 7;
            uint16_t exponent : 8;
            uint16_t sign : 1;
        };
    };

    constexpr uint16_t exp_bits() const noexcept;
    constexpr uint16_t frac_bits() const noexcept;

    friend API_FUNC_EXPORT BFloat16 operator-(BFloat16 a) noexcept;
    friend API_FUNC_EXPORT BFloat16 operator+(BFloat16 a, BFloat16 b) noexcept;
    friend API_FUNC_EXPORT BFloat16 operator-(BFloat16 a, BFloat16 b) noexcept;
    friend API_FUNC_EXPORT BFloat16 operator*(BFloat16 a, BFloat16 b) noexcept;
    friend API_FUNC_EXPORT BFloat16 operator/(BFloat16 a, BFloat16 b) noexcept;
};

POP_VISIBILITY()

//
// ** If you make a change which causes any of the static_assert below
// ** to be violated, please reconsider the change before removing the assert.
// ** Such a change will affect the size and performance of code using BFloat16
// ** (E.g. it may not be possible to pass a BFloat16 by value, without extra
// ** overhead). Do NOT add 'noexcept' to defaulted methods to make the asserts
// ** go away.
static_assert(std::is_nothrow_copy_constructible<BFloat16>::value);
static_assert(std::is_nothrow_move_constructible<BFloat16>::value);
static_assert(std::is_nothrow_assignable<BFloat16, BFloat16>::value);
static_assert(std::is_nothrow_move_assignable<BFloat16>::value);
static_assert(std::is_nothrow_destructible<BFloat16>::value);
static_assert(std::is_trivially_copyable<BFloat16>::value);
static_assert(std::is_standard_layout<BFloat16>::value);

inline constexpr BFloat16 BFloat16::from_raw(uint16_t v) noexcept
{
    BFloat16 f;
    f.d = v;
    return f;
}

inline constexpr BFloat16::BFloat16(float f) noexcept : d(0)
{
    union U {
        constexpr U(float f) : as_f32(f) {}
        float as_f32;
        uint32_t as_u32;
    } const u(f);

    // Preserve NaN values
    // The only potential NaN values that can be lost are the ones that have an exp=0xFF and a non 0 bit in the 16 lsb
    if ((u.as_u32 & 0x7F80FFFF) > 0x7F800000) {
        d = 0x7FA0u; // qnan
        return;
    }
    // just round it to nearest multiple of 2^16.
    // Preserves sign, zeros, inf.
    // Some values will round up to next exponent, or from EXP=126 to inf,
    // or from large subnormal to 'smallest normal';
    // or down from small subnormal to 0.0. But all of that is numerically correct.
    //
    uint32_t val = u.as_u32;
    // rounding bias is 0x7fff if bit 16 is 0, and 0x8000
    // if bit 16 is 1; so we round-to-even when halfway
    uint32_t const bias = 0x7FFFU + ((val >> 16u) & 1u);
    val += bias;
    // upper 16 bits are the result.
    d = static_cast<uint16_t>(val >> 16u);
}

inline constexpr uint16_t BFloat16::exp_bits() const noexcept
{
    return d & 0x7F80u;
}

inline constexpr uint16_t BFloat16::frac_bits() const noexcept
{
    return d & 0x7Fu;
}

inline constexpr bool BFloat16::is_zero() const noexcept
{
    return (exp_bits() | frac_bits()) == 0x0000;
}

inline constexpr bool BFloat16::is_neg() const noexcept
{
    return (d & 0x8000u) != 0;
}

inline constexpr BFloat16 BFloat16::zero(bool neg) noexcept
{
    return BFloat16::from_raw((neg) ? 0x8000u : 0x0);
}

inline constexpr BFloat16 BFloat16::qnan() noexcept
{
    return BFloat16::from_raw(0x7FA0u);
}

inline constexpr BFloat16 BFloat16::snan() noexcept
{
    return BFloat16::from_raw(0x7FC0u); // impl defined
}

inline constexpr BFloat16 BFloat16::inf(bool neg) noexcept
{
    return BFloat16::from_raw((neg) ? 0xFF80u : 0x7F80u);
}

inline constexpr BFloat16::operator float() const noexcept
{
    union U {
        constexpr U(uint32_t u) : as_u32(u) {}
        float as_f32;
        uint32_t as_u32;
    } u(static_cast<uint32_t>(raw()) << 16);
    return u.as_f32;
}

inline constexpr bool BFloat16::is_norm() const noexcept
{
    return is_zero() || (!is_inf() && !is_nan() && !is_subnorm());
}

inline constexpr bool BFloat16::is_inf() const noexcept
{
    return exp_bits() == 0x7F80u && frac_bits() == 0x0u;
}

inline constexpr bool BFloat16::is_nan() const noexcept
{
    return exp_bits() == 0x7F80u && frac_bits() != 0x0u;
}

inline constexpr bool BFloat16::is_subnorm() const noexcept
{
    return exp_bits() == 0x0000 && frac_bits() != 0x0000;
}

inline constexpr bool BFloat16::is_finite() const noexcept
{
    return is_norm() || is_subnorm();
}

inline constexpr uint16_t BFloat16::frac() const noexcept
{
    if (is_zero()) {
        return 0x0u;
    }
    uint16_t f = frac_bits();
    if (is_norm()) f |= 1u << 7u;
    return f;
}

inline constexpr int16_t BFloat16::exp() const noexcept
{
    int16_t const e = static_cast<int16_t>(exp_bits() >> 7u);
    return e != 0 ? e - bias() : exp_min();
}

PUSH_VISIBILITY(default)
template <> class API_EXPORT std::numeric_limits<BFloat16> {
  public:
    static constexpr bool is_specialized = true;
    static constexpr bool is_signed = true;
    static constexpr bool is_integer = false;
    static constexpr bool is_exact = false;
    static constexpr bool has_infinity = true;
    static constexpr bool has_quiet_NaN = true;
    static constexpr bool has_signaling_NaN = true;
    static constexpr auto has_denorm = std::denorm_present;
    static constexpr bool has_denorm_loss = false; // libc++
    static constexpr auto round_style = std::round_to_nearest;
    static constexpr bool is_iec559 = false;
    static constexpr bool is_bounded = true;
    static constexpr bool is_modulo = false;
    static constexpr int digits = 8;
    static constexpr int digits10 = 2; // floor((digits-1) * log10(2))
    static constexpr int max_digits10 = 4; // ceil(digits * log10(2) + 1)
    static constexpr int radix = 2;
    static constexpr int min_exponent = -126;
    static constexpr int min_exponent10 = -37; // float32 value
    static constexpr int max_exponent = 127;
    static constexpr int max_exponent10 = 38; // largest finite val = 3.3895314E38
    static constexpr bool traps = false;
    static constexpr bool tinyness_before = false; // libc++

    static constexpr BFloat16 min() noexcept; // returns min positive normal
    static constexpr BFloat16 lowest() noexcept; // returns true min
    static constexpr BFloat16 max() noexcept; // max positive
    static constexpr BFloat16 epsilon() noexcept; // step at 1.0
    static constexpr BFloat16 round_error() noexcept; // 0.5
    static constexpr BFloat16 infinity() noexcept;
    static constexpr BFloat16 quiet_NaN() noexcept;
    static constexpr BFloat16 signaling_NaN() noexcept;
    static constexpr BFloat16 denorm_min() noexcept; // min positive denorm
};

POP_VISIBILITY()

constexpr BFloat16 std::numeric_limits<BFloat16>::min() noexcept
{
    // 0 0000 0001 0000000
    return BFloat16::from_raw(0x80u);
}

constexpr BFloat16 std::numeric_limits<BFloat16>::lowest() noexcept
{
    // -2^127 * (1.9921875)  ; 1 1111 1110 1111 111
    return BFloat16::from_raw(0xFF7Fu); // -3.3895314E38
}

constexpr BFloat16 std::numeric_limits<BFloat16>::max() noexcept
{
    return BFloat16::from_raw(0x7f7fu);
}

constexpr BFloat16 std::numeric_limits<BFloat16>::epsilon() noexcept
{
    // 2^-7 * (1)     ; 0 01111000 0000000
    return BFloat16::from_raw(0x3C00u); // next_after_1.0 - 1.0
}

constexpr BFloat16 std::numeric_limits<BFloat16>::round_error() noexcept
{
    // 2^-1 * (1)      ; 0 01111110 0000000
    return BFloat16::from_raw(0x3F00u); // 0.5
}

constexpr BFloat16 std::numeric_limits<BFloat16>::infinity() noexcept
{
    return BFloat16::inf(false);
}

constexpr BFloat16 std::numeric_limits<BFloat16>::quiet_NaN() noexcept
{
    return BFloat16::qnan();
}

constexpr BFloat16 std::numeric_limits<BFloat16>::signaling_NaN() noexcept
{
    return BFloat16::snan();
}

constexpr BFloat16 std::numeric_limits<BFloat16>::denorm_min() noexcept
{
    return BFloat16::from_raw(0x0001u);
}

#endif // BFLOAT16_H
