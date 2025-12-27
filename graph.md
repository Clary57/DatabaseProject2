flowchart LR
  %% ========== Top Level IO ==========
  subgraph TOP[Top: sys_top]
    CLK[clk/rst_n]
    CMD[command[2:0]\nscalar_command]
    BTN[btn_confirm\nbtn_countdown]
    UARTIN[uart_rxd]
    UAROUT[uart_txd]
    LEDS[led]
    SEG1[dk1_segments/dk1_sel]
    SEG2[dk2_segments/dk2_sel]
  end

  %% ========== Frontend ==========
  subgraph FE[Frontend: UART/Buttons]
    DEB[btn_debouncer]
    RX[uart_rx]
    TX[uart_tx]
  end

  %% ========== Mode Control ==========
  subgraph CTRL[Mode Control]
    CC[Central_Controller\n(6 mode enables)]
    MUX[UART TX MUX\n(sys_top always @*)]
  end

  %% ========== Input/Generate ==========
  subgraph INGEN[Input / Generate]
    PARSER[input_mode\n(UART matrix parse)]
    GEN[generate_mode\n(random matrix gen)]
    PR_PARSE[matrix_printer\n(parse print)]
    PR_GEN[matrix_printer\n(gen print)]
  end

  %% ========== Storage ==========
  subgraph ST[Storage]
    STORE[matrix_storage\nstorage[0:49]\ncount[0:24]\ninfo_table/total_count]
    ARB[Read Arbitration\nrand > selector > spec]
  end

  %% ========== Display ==========
  subgraph DISP[Display Mode]
    SEL[matrix_selector_display\n(table->dim->spec->id->read->print)]
    TABLE[print_table\n(info_table)]
    SPEC[print_specified_dim_matrix\n(cnt header + read loop)]
    PR_SPEC[matrix_printer\n(spec matrices)]
    PR_SEL[matrix_printer\n(selected matrix)]
  end

  %% ========== Calculation ==========
  subgraph CALC[Calculation Mode]
    RAND[rand_sel_from_store\n(random operands + scalar)]
    ALU[matrix_alu\n(transpose/scalar/add/mul)]
    PR_RES[matrix_printer16\n(16-bit result)]
    PR_CALC[matrix_printer\n(print operands)]
    CALC_FSM[Calc FSM\n(inside sys_top)]
  end

  %% ========== Convolution ==========
  subgraph CONV[Convolution Mode]
    ROM[input_image_rom]
    CONVENG[convolution_engine\n(kernel via UART\nROM->linebuf->MAC\nresult(80x16)]
    CONVPR[conv_matrix_printer\n(8x10, 16-bit)]
    CYC[print_cycles\n(cycles_counter)]
  end

  %% ========== Settings / Countdown / Display ==========
  subgraph UX[Settings / Countdown / Indicators]
    SET[setting_subsystem\n(param_value=countdown_time)]
    TIMER[countdown_controller\n(error countdown)]
    OPLED[output_cal_mod\n(dk2 shows op/conv)]
  end

  %% Wiring: top-level IO to frontend
  BTN --> DEB
  UARTIN --> RX
  MUX --> TX --> UAROUT

  %% Mode control
  CMD --> CC
  DEB --> CC
  CC -->|data_input_mode_en| PARSER
  CC -->|generate_mode_en| GEN
  CC -->|display_mode_en| SEL
  CC -->|calculation_mode_en| CALC_FSM
  CC -->|conv_mode_en| CONVENG
  CC -->|settings_mode_en| SET

  %% UART RX fanout
  RX -->|uart_rx_data/done| PARSER
  RX -->|uart_rx_data/done| GEN
  RX -->|uart_rx_data/done| SEL
  RX -->|uart_rx_data/done| CONVENG
  RX -->|uart_rx_data/done| SET

  %% Store write path
  PARSER -->|parsed_m/n + matrix_flat| STORE
  GEN -->|gen_m/n + gen_flow| STORE

  %% Print (parse/gen)
  PARSER -->|parsed_matrix_flat| PR_PARSE
  GEN -->|gen_flow| PR_GEN

  %% Display path
  STORE -->|info_table/total_count| TABLE
  STORE -->|info_table| SPEC
  SEL -->|print_table_start| TABLE
  SEL -->|print_spec_start + dim| SPEC
  SPEC -->|matrix_flat| PR_SPEC
  SEL -->|read request| ARB --> STORE
  SPEC -->|read request| ARB
  RAND -->|read request| ARB
  STORE -->|rd_data_flow/rd_ready| SEL
  STORE -->|rd_data_flow/rd_ready| SPEC
  STORE -->|rd_data_flow/rd_ready| RAND
  SEL -->|selected matrix| PR_SEL

  %% Calculation path
  CALC_FSM --> RAND
  CALC_FSM -->|matrix_selected[0/1], dims| ALU
  RAND -->|matrix1/2 + dims| ALU
  RAND -->|scalar_out| ALU
  ALU -->|result_flat(16-bit)| PR_RES
  CALC_FSM -->|print operands| PR_CALC

  %% Convolution path
  ROM --> CONVENG
  CONVENG -->|matrix_data(80x16)| CONVPR
  CONVENG -->|cycles_counter_out| CYC

  %% UART TX MUX sources
  PR_PARSE --> MUX
  PR_GEN --> MUX
  TABLE --> MUX
  SPEC --> MUX
  PR_SPEC --> MUX
  PR_SEL --> MUX
  PR_RES --> MUX
  PR_CALC --> MUX
  CONVPR --> MUX
  CYC --> MUX

  %% UX helpers
  SET -->|countdown_time| TIMER
  CALC_FSM -->|timer_start/end_timer| TIMER
  OPLED --> SEG2
  TIMER --> SEG1
  CC --> LEDS
