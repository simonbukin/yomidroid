// Compatibility shim: provide std::ranges::to for NDK 26.1's libc++ (LLVM 17),
// which ships C++23 ranges adaptors (e.g. views::as_rvalue) but not the
// std::ranges::to container conversion (P1206, landed in libc++ 18).
//
// Hoshidicts uses the deduced template form `range | std::ranges::to<std::vector>()`
// in a handful of spots. We inject this header into the hoshidicts compilation
// units via a CMake force-include (-include) so the vendored submodule sources
// stay unmodified. The whole shim is guarded on the standard feature-test macro,
// so it silently disappears the moment the project moves to an NDK whose libc++
// implements std::ranges::to natively.

#pragma once

#include <ranges>
#include <type_traits>
#include <utility>
#include <vector>

#if !defined(__cpp_lib_ranges_to_container)

namespace std {
namespace ranges {

// Pipe-able closure for the `range | to<Container>()` form where the container's
// element type is deduced from the range. Only the template-template form used
// by Hoshidicts is provided.
template <template <class...> class Container>
struct __yomi_to_closure {
  template <class Range>
  friend auto operator|(Range&& r, __yomi_to_closure) {
    using Value = std::remove_cvref_t<std::ranges::range_value_t<Range>>;
    Container<Value> out;
    for (auto&& elem : std::forward<Range>(r)) {
      out.push_back(static_cast<Value>(std::forward<decltype(elem)>(elem)));
    }
    return out;
  }
};

template <template <class...> class Container>
[[nodiscard]] __yomi_to_closure<Container> to() {
  return {};
}

}  // namespace ranges
}  // namespace std

#endif  // !__cpp_lib_ranges_to_container
