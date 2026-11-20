(module
  (func (export "convert") (param f64 f64 f64) (result f64)
    local.get 0
    local.get 1
    f64.mul
    local.get 2
    f64.add))
