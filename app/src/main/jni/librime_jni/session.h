// SPDX-FileCopyrightText: 2015 - 2025 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

#pragma once

#include <rime_api.h>

#include <stdexcept>

// RAII会话管理：自动创建和销毁Rime会话
class SessionHolder {
 public:
  SessionHolder() {
    auto *api = rime_get_api();
    id_ = api->create_session();

    if (!id_) {
      throw std::runtime_error("Failed to create session");
    }
  }

  SessionHolder(SessionHolder &&) = delete;

  ~SessionHolder() {
    if (id_) {
      rime_get_api()->destroy_session(id_);
    }
  }

  RimeSessionId id() const { return id_; }

 private:
  RimeSessionId id_ = 0;
};
